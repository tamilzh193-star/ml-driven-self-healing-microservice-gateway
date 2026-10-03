#!/usr/bin/env python3
"""
Phase 10: Gateway <-> ML Service End-to-End Integration Verification
===================================================================
Tests the continuous pipeline:
Actuator -> Gateway Telemetry Collector -> Flask ML Service -> Anomaly Classification
"""

import sys
import time
import json
import urllib.request
import urllib.error

GATEWAY_URL = "http://localhost:8080"
PAYMENT_CHAOS_URL = "http://localhost:8082/chaos"


def get(url, timeout=5):
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode()) if e.fp else {}
    except Exception as e:
        return 0, str(e)


def post(url, timeout=5):
    try:
        req = urllib.request.Request(url, method="POST", data=b"")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode()
            try:
                return resp.status, json.loads(text)
            except json.JSONDecodeError:
                return resp.status, text
    except urllib.error.HTTPError as e:
        text = e.read().decode() if e.fp else ""
        try:
            return e.code, json.loads(text)
        except json.JSONDecodeError:
            return e.code, text
    except Exception as e:
        return 0, str(e)


def wait_for_services():
    print("[INIT] Waiting for Gateway and Microservices to be UP...", flush=True)
    services = {
        "Gateway": f"{GATEWAY_URL}/actuator/health",
        "Order": "http://localhost:8081/actuator/health",
        "Payment": "http://localhost:8082/actuator/health",
        "Inventory": "http://localhost:8083/actuator/health",
        "ML Service": "http://localhost:5000/health"
    }
    for attempt in range(50):
        all_up = True
        for name, url in services.items():
            code, data = get(url, timeout=3)
            if code != 200 or not isinstance(data, dict) or data.get("status") != "UP":
                all_up = False
                break
        if all_up:
            print("  [+] All 5 services (Gateway, Order, Payment, Inventory, ML) are UP!", flush=True)
            return True
        time.sleep(2)
    print("  [-] Timed out waiting for services.", flush=True)
    return False


def test_pipeline():
    if not wait_for_services():
        sys.exit(1)

    print("\n" + "=" * 65)
    print("      GATEWAY <-> ML SERVICE INTEGRATION VERIFICATION")
    print("=" * 65)

    # Step 1: Clean chaos reset
    print("\n[STEP 1] Resetting chaos across all services...")
    post("http://localhost:8081/chaos/reset")
    post("http://localhost:8082/chaos/reset")
    post("http://localhost:8083/chaos/reset")

    # Step 2: Check Gateway ML Status endpoint
    print("\n[STEP 2] Testing GET /gateway/ml/status...")
    code, data = get(f"{GATEWAY_URL}/gateway/ml/status")
    print(f"  Status code: {code}")
    print(f"  ML Service Status: {data.get('status')}")
    print(f"  Ping Latency: {data.get('pingLatencyMs')} ms")
    assert code == 200, f"Expected 200, got {code}"
    assert data.get("status") == "UP", "ML Service status not UP"
    print("  --> PASS: Gateway successfully communicates with ML Service.")

    # Step 3: Trigger on-demand evaluation and check baseline normalcy
    print("\n[STEP 3] Stimulating business traffic and evaluating baseline ML state...")
    for _ in range(5):
        get(f"{GATEWAY_URL}/orders")
        get(f"{GATEWAY_URL}/payments")
        get(f"{GATEWAY_URL}/inventory")
    time.sleep(1)

    code, eval_data = post(f"{GATEWAY_URL}/gateway/ml/evaluate")
    print(f"  Evaluation response code: {code}")
    code, latest = get(f"{GATEWAY_URL}/gateway/ml/latest")
    print(f"  GET /gateway/ml/latest: {json.dumps(latest, indent=2)}")
    assert code == 200, f"Expected 200, got {code}"
    
    # Verify payment-service is NORMAL
    payment_pred = latest.get("payment-service", {})
    assert payment_pred.get("prediction") == "NORMAL", f"Expected NORMAL, got {payment_pred.get('prediction')}"
    print(f"  --> PASS: Baseline state correctly evaluated as NORMAL (Score: {payment_pred.get('anomaly_score')})")

    # Step 4: Inject Slow Latency Fault into Payment Service
    print("\n[STEP 4] Injecting 1500ms delay into payment-service: POST /chaos/slow?delayMs=1500...")
    code, chaos_res = post(f"{PAYMENT_CHAOS_URL}/slow?delayMs=1500")
    print(f"  Chaos activation response: {chaos_res}")
    
    # Send traffic to payment-service to trigger the delay
    print("  Sending traffic through Gateway to /payments...")
    for _ in range(3):
        get(f"{GATEWAY_URL}/payments", timeout=10)

    # Trigger evaluation
    post(f"{GATEWAY_URL}/gateway/ml/evaluate")
    time.sleep(2)
    code, anomaly_latest = get(f"{GATEWAY_URL}/gateway/ml/latest")
    print(f"  GET /gateway/ml/latest after fault injection:\n{json.dumps(anomaly_latest, indent=2)}")

    payment_anomaly = anomaly_latest.get("payment-service", {})
    assert payment_anomaly.get("prediction") == "ANOMALY", f"Expected ANOMALY, got {payment_anomaly.get('prediction')}"
    assert payment_anomaly.get("is_anomaly") is True, "Expected is_anomaly=True"
    assert payment_anomaly.get("anomaly_score") < 0, "Expected negative anomaly score"
    print(f"  --> PASS: Fault successfully detected by Isolation Forest! Score: {payment_anomaly.get('anomaly_score')}")

    # Step 5: Reset fault and verify recovery
    print("\n[STEP 5] Resetting fault: POST /chaos/reset on payment-service...")
    post(f"{PAYMENT_CHAOS_URL}/reset")
    time.sleep(1)

    print("  Sending normal traffic through Gateway to /payments...")
    for _ in range(5):
        get(f"{GATEWAY_URL}/payments")

    # Trigger evaluation
    post(f"{GATEWAY_URL}/gateway/ml/evaluate")
    time.sleep(2)
    code, recovered_latest = get(f"{GATEWAY_URL}/gateway/ml/latest")
    print(f"  GET /gateway/ml/latest after recovery:\n{json.dumps(recovered_latest, indent=2)}")

    payment_recovered = recovered_latest.get("payment-service", {})
    assert payment_recovered.get("prediction") == "NORMAL", f"Expected NORMAL, got {payment_recovered.get('prediction')}"
    print(f"  --> PASS: Service successfully recovered to NORMAL baseline! Score: {payment_recovered.get('anomaly_score')}")

    print("\n" + "=" * 65)
    print("      ALL PHASE 10 INTEGRATION VERIFICATIONS PASSED!")
    print("=" * 65)


if __name__ == "__main__":
    test_pipeline()
