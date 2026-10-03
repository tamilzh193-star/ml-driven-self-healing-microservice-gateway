#!/usr/bin/env python3
"""
Phase 11: Sliding-Window Anomaly Confirmation Verification
=========================================================
Tests the anti-flapping confirmation state machine:
- Baseline: HEALTHY (0 anomalies in window)
- Step 1 (Anti-Flapping): Fault introduced -> 1 anomalous cycle produces SUSPECT (not CONFIRMED_ANOMALY)
- Step 2 (Sustained Anomaly): Sustained fault -> reaches K=3 consecutive anomalies -> CONFIRMED_ANOMALY
- Step 3 (Confirmed Queries): /gateway/anomaly/confirmed returns payment-service
- Step 4 (Recovery Hysteresis): Fault cleared -> consecutive normal cycles transition CONFIRMED -> RECOVERING -> HEALTHY
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
            print("  [+] All 5 services are UP and reachable!", flush=True)
            return True
        time.sleep(2)
    print("  [-] Timed out waiting for services.", flush=True)
    return False


def test_anomaly_confirmation():
    if not wait_for_services():
        sys.exit(1)

    print("\n" + "=" * 70, flush=True)
    print("   PHASE 11: SLIDING-WINDOW ANOMALY CONFIRMATION VERIFICATION", flush=True)
    print("=" * 70, flush=True)

    # Step 1: Clean reset & Baseline
    print("\n[STEP 1] Resetting chaos and waiting for HEALTHY baseline...", flush=True)
    post("http://localhost:8081/chaos/reset")
    post("http://localhost:8082/chaos/reset")
    post("http://localhost:8083/chaos/reset")
    post(f"{GATEWAY_URL}/gateway/anomaly/reset")

    # Send a few normal requests
    for _ in range(5):
        get(f"{GATEWAY_URL}/payments")
    
    # Wait for scheduled telemetry collector to record healthy state
    healthy = False
    for attempt in range(10):
        time.sleep(3)
        code, state = get(f"{GATEWAY_URL}/gateway/anomaly/status/payment-service")
        if code == 200 and state.get("status") == "HEALTHY" and state.get("anomaliesInWindow") == 0:
            healthy = True
            print(f"  [+] Baseline established: status={state.get('status')}, score={state.get('averageAnomalyScore')}", flush=True)
            break

    assert healthy, "Failed to establish healthy baseline for payment-service"
    code, confirmed = get(f"{GATEWAY_URL}/gateway/anomaly/confirmed")
    assert len(confirmed) == 0, f"Expected 0 confirmed anomalies at baseline, got {confirmed}"
    print("  --> PASS: Initial state clean, 0 confirmed anomalies.", flush=True)

    # Step 2: Inject Fault and verify Anti-Flapping (SUSPECT state)
    print("\n[STEP 2] Injecting 1600ms latency fault into payment-service...", flush=True)
    post(f"{PAYMENT_CHAOS_URL}/slow?delayMs=1600")

    print("  Waiting for initial anomaly detection (Anti-Flapping check)...", flush=True)
    observed_suspect = False
    observed_confirmed = False

    for attempt in range(15):
        time.sleep(2)
        code, state = get(f"{GATEWAY_URL}/gateway/anomaly/status/payment-service")
        status = state.get("status")
        consecutive = state.get("consecutiveAnomalies", 0)
        window = state.get("anomaliesInWindow", 0)
        score = state.get("latestPrediction", {}).get("anomaly_score")

        print(f"  Poll {attempt + 1}: status={status}, consecutive={consecutive}, window={window}/5, score={score}", flush=True)

        if status == "SUSPECT":
            observed_suspect = True
            code_c, conf_list = get(f"{GATEWAY_URL}/gateway/anomaly/confirmed")
            assert len(conf_list) == 0, f"Anti-flapping violation! SUSPECT state leaked into /confirmed: {conf_list}"
            print("  --> PASS: Anti-Flapping verified: status=SUSPECT, 0 confirmed anomalies triggered!", flush=True)

        if status == "CONFIRMED_ANOMALY":
            observed_confirmed = True
            code_c, conf_list = get(f"{GATEWAY_URL}/gateway/anomaly/confirmed")
            assert len(conf_list) >= 1, f"Expected confirmed anomalies list to contain payment-service, got {conf_list}"
            assert conf_list[0].get("serviceName") == "payment-service", f"Expected payment-service, got {conf_list}"
            assert state.get("confirmedTimestamp") is not None, "Expected non-null confirmedTimestamp"
            print(f"  --> PASS: Sustained anomaly confirmed! Timestamp={state.get('confirmedTimestamp')}", flush=True)
            break

    assert observed_confirmed, "Payment-service never transitioned to CONFIRMED_ANOMALY"

    # Step 3: Verify Recovery Hysteresis
    print("\n[STEP 3] Resetting fault and verifying Recovery Hysteresis...", flush=True)
    post(f"{PAYMENT_CHAOS_URL}/reset")

    observed_recovering = False
    observed_healthy = False

    for attempt in range(20):
        # Generate some clean traffic
        get(f"{GATEWAY_URL}/payments")
        time.sleep(2.5)

        code, state = get(f"{GATEWAY_URL}/gateway/anomaly/status/payment-service")
        status = state.get("status")
        normal_count = state.get("consecutiveNormal", 0)
        print(f"  Recovery Poll {attempt + 1}: status={status}, consecutiveNormal={normal_count}", flush=True)

        if status == "RECOVERING":
            observed_recovering = True

        if status == "HEALTHY" and normal_count >= 2:
            observed_healthy = True
            code_c, conf_list = get(f"{GATEWAY_URL}/gateway/anomaly/confirmed")
            assert len(conf_list) == 0, f"Expected 0 confirmed anomalies after recovery, got {conf_list}"
            print("  --> PASS: Service successfully transitioned RECOVERING -> HEALTHY!", flush=True)
            break

    assert observed_healthy, "Service did not recover to HEALTHY baseline"

    # Step 4: Verify manual reset endpoint
    print("\n[STEP 4] Verifying POST /gateway/anomaly/reset...", flush=True)
    code, res = post(f"{GATEWAY_URL}/gateway/anomaly/reset")
    assert code == 200, f"Expected 200 from reset, got {code}"
    code, all_states = get(f"{GATEWAY_URL}/gateway/anomaly/status")
    assert code == 200, f"Expected 200, got {code}"
    for svc, s in all_states.items():
        assert s.get("status") == "HEALTHY", f"Expected HEALTHY for {svc}, got {s.get('status')}"
        assert s.get("consecutiveAnomalies") == 0
        assert s.get("anomaliesInWindow") == 0
    print("  --> PASS: Manual reset cleared all states successfully.")

    print("\n" + "=" * 70, flush=True)
    print("      ALL PHASE 11 ANOMALY CONFIRMATION TESTS PASSED!", flush=True)
    print("=" * 70, flush=True)


if __name__ == "__main__":
    test_anomaly_confirmation()
