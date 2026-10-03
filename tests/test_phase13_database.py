#!/usr/bin/env python3
"""
Phase 13: Database Integration & Persistence Layer Verification
==============================================================
Tests the relational persistence of telemetry observations,
ML anomaly predictions, and autonomous healing audit trails.
"""

import sys
import time
import json
import urllib.request
import urllib.error

GATEWAY_URL = "http://localhost:8080"
PAYMENT_CHAOS_URL = "http://localhost:8082/chaos"


def get(url, timeout=10):
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode()) if e.fp else {}
    except Exception as e:
        print(f"  [GET ERROR] {url}: {e}", flush=True)
        return 0, str(e)


def post(url, timeout=10):
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
        print(f"  [POST ERROR] {url}: {e}", flush=True)
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


def test_database_persistence():
    if not wait_for_services():
        sys.exit(1)

    print("\n" + "=" * 70, flush=True)
    print("      PHASE 13: DATABASE PERSISTENCE LAYER VERIFICATION", flush=True)
    print("=" * 70, flush=True)

    # Step 1: Clean reset
    print("\n[STEP 1] Resetting chaos, healing states, and database tables...", flush=True)
    post("http://localhost:8081/chaos/reset")
    post("http://localhost:8082/chaos/reset")
    post("http://localhost:8083/chaos/reset")
    post(f"{GATEWAY_URL}/gateway/anomaly/reset")
    post(f"{GATEWAY_URL}/gateway/healing/reset")
    post(f"{GATEWAY_URL}/gateway/db/reset")

    # Step 2: Database Connectivity & Status Check
    print("\n[STEP 2] Verifying GET /gateway/db/status...", flush=True)
    code, status = get(f"{GATEWAY_URL}/gateway/db/status")
    print(f"  Database Status: {json.dumps(status, indent=2)}", flush=True)
    assert code == 200, f"Expected 200, got {code}"
    assert status.get("persistenceEnabled") is True, "Expected persistenceEnabled=True"
    assert status.get("databaseStatus") == "CONNECTED", f"Expected CONNECTED, got {status.get('databaseStatus')}"
    assert status.get("telemetryRowCount") == 0, f"Expected 0 telemetry records after reset, got {status.get('telemetryRowCount')}"
    print(f"  [+] Connected to: {status.get('databaseProductName')} ({status.get('databaseProductVersion')})", flush=True)
    print("  --> PASS: Database connection healthy and tables initialized.")

    # Step 3: Stimulate traffic & Verify Telemetry Persistence
    print("\n[STEP 3] Generating traffic and verifying asynchronous telemetry persistence...", flush=True)
    for _ in range(5):
        get(f"{GATEWAY_URL}/orders")
        get(f"{GATEWAY_URL}/payments")
        get(f"{GATEWAY_URL}/inventory")

    # Wait for scheduled telemetry collector cycle (5s)
    print("  Waiting 7 seconds for telemetry collection and async DB insert...", flush=True)
    time.sleep(7)

    code, tel_records = get(f"{GATEWAY_URL}/gateway/db/telemetry?limit=10")
    print(f"  Persisted Telemetry Sample Count: {len(tel_records)}", flush=True)
    assert code == 200, f"Expected 200, got {code}"
    assert len(tel_records) >= 3, f"Expected at least 3 telemetry records in DB, got {len(tel_records)}"

    sample = tel_records[0]
    print(f"  Sample Telemetry Record in DB: {json.dumps(sample, indent=2)}", flush=True)
    for expected_col in ["SERVICE", "CPU", "MEMORY", "LATENCY", "ERROR_RATE", "REQUEST_RATE"]:
        # Case insensitive key check
        found = any(k.upper() == expected_col for k in sample.keys())
        assert found, f"Missing column {expected_col} in persisted telemetry record: {sample}"

    code, updated_status = get(f"{GATEWAY_URL}/gateway/db/status")
    assert updated_status.get("telemetryRowCount") >= 3
    print(f"  --> PASS: Telemetry records successfully persisted to relational table! Total rows: {updated_status.get('telemetryRowCount')}", flush=True)

    # Step 4: Verify Healing Audit Log Persistence
    print("\n[STEP 4] Executing autonomous healing and verifying audit log persistence...", flush=True)
    # Trigger a clean healing remediation
    post(f"{PAYMENT_CHAOS_URL}/slow?delayMs=1600")
    time.sleep(1)

    # Trigger healing on payment-service
    code, heal_record = post(f"{GATEWAY_URL}/gateway/healing/trigger/payment-service?force=true")
    print(f"  Remediation Triggered: action={heal_record.get('actionType')}, recovery={heal_record.get('recoveryStatus')}", flush=True)

    # Clear chaos
    post(f"{PAYMENT_CHAOS_URL}/reset")
    time.sleep(2)

    # Query DB for healing audit records
    code, healing_db_records = get(f"{GATEWAY_URL}/gateway/db/healing?limit=10")
    print(f"  Persisted Healing Audit Logs Count: {len(healing_db_records)}", flush=True)
    assert code == 200, f"Expected 200, got {code}"
    assert len(healing_db_records) >= 1, f"Expected at least 1 healing audit record in DB, got {len(healing_db_records)}"

    sample_healing = healing_db_records[0]
    print(f"  Sample Persisted Healing Audit in DB: {json.dumps(sample_healing, indent=2)}", flush=True)
    assert any(k.upper() == "SERVICE_NAME" for k in sample_healing.keys()), "Missing SERVICE_NAME"
    assert any(k.upper() == "ACTION_TYPE" for k in sample_healing.keys()), "Missing ACTION_TYPE"
    assert any(k.upper() == "ACTION_STATUS" for k in sample_healing.keys()), "Missing ACTION_STATUS"
    print("  --> PASS: Healing audit record successfully persisted to relational table!", flush=True)

    # Step 5: Verify Filtered Telemetry Query
    print("\n[STEP 5] Testing parameterized service queries (GET /gateway/db/telemetry?service=payment-service)...", flush=True)
    code, payment_telemetry = get(f"{GATEWAY_URL}/gateway/db/telemetry?service=payment-service&limit=5")
    assert code == 200, f"Expected 200, got {code}"
    for rec in payment_telemetry:
        # Check service is payment-service
        svc = next(v for k, v in rec.items() if k.upper() == "SERVICE")
        assert svc == "payment-service", f"Expected payment-service, got {svc}"
    print(f"  --> PASS: Parameterized query returned {len(payment_telemetry)} records exclusively for payment-service.")

    # Step 6: Verify Database Reset API
    print("\n[STEP 6] Testing POST /gateway/db/reset...", flush=True)
    code, res = post(f"{GATEWAY_URL}/gateway/db/reset")
    assert code == 200, f"Expected 200 from db reset, got {code}"
    code, final_status = get(f"{GATEWAY_URL}/gateway/db/status")
    assert final_status.get("telemetryRowCount") == 0
    assert final_status.get("healingAuditRowCount") == 0
    print("  --> PASS: Administrative reset cleanly cleared all database tables.")

    print("\n" + "=" * 70, flush=True)
    print("      ALL PHASE 13 DATABASE PERSISTENCE TESTS PASSED!", flush=True)
    print("=" * 70, flush=True)


if __name__ == "__main__":
    test_database_persistence()
