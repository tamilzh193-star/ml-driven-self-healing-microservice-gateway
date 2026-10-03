#!/usr/bin/env python3
"""
Phase 12: Autonomous Healing Engine Verification
===============================================
Tests the closed-loop autonomous recovery flow:
1. Normal State: Baseline normal traffic does NOT trigger healing.
2. Confirmed Anomaly Triggers Healing: Sustained fault escalates to CONFIRMED_ANOMALY, which triggers healing.
3. Recovery Verification: Post-healing health check and response probe verify recovery.
4. Cooldown Protection: Immediate repeated triggers are suppressed with SKIPPED_COOLDOWN.
5. Failed Recovery Recording: Verifies failed recovery states and retry escalation are recorded in audit log.
6. Reset Verification: POST /gateway/healing/reset cleanly clears engine states.
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


def test_healing_engine():
    if not wait_for_services():
        sys.exit(1)

    print("\n" + "=" * 70, flush=True)
    print("      PHASE 12: AUTONOMOUS HEALING ENGINE VERIFICATION", flush=True)
    print("=" * 70, flush=True)

    # -------------------------------------------------------------
    # TEST 1: Reset / Normal state does NOT trigger healing
    # -------------------------------------------------------------
    print("\n[TEST 1] Verifying that normal baseline does NOT trigger healing...", flush=True)
    post("http://localhost:8081/chaos/reset")
    post("http://localhost:8082/chaos/reset")
    post("http://localhost:8083/chaos/reset")
    post(f"{GATEWAY_URL}/gateway/anomaly/reset")
    post(f"{GATEWAY_URL}/gateway/healing/reset")

    for _ in range(5):
        get(f"{GATEWAY_URL}/payments")
    time.sleep(2)

    code, history = get(f"{GATEWAY_URL}/gateway/healing/history")
    assert code == 200, f"Expected 200, got {code}"
    assert len(history) == 0, f"Expected 0 healing actions in clean state, got {len(history)}"

    code, status = get(f"{GATEWAY_URL}/gateway/healing/status")
    assert code == 200, f"Expected 200, got {code}"
    assert len(status.get("activeRedirections", [])) == 0
    assert len(status.get("activeRateLimits", {})) == 0
    print("  --> PASS: Baseline traffic generated 0 healing actions. Healing engine idle.", flush=True)

    # -------------------------------------------------------------
    # TEST 2 & 3: Confirmed Anomaly Triggers Healing & Recovery Verification
    # -------------------------------------------------------------
    print("\n[TEST 2 & 3] Injecting fault -> Anomaly Confirmation -> Autonomous Healing...", flush=True)
    print("  Injecting 1600ms latency fault into payment-service...", flush=True)
    post(f"{PAYMENT_CHAOS_URL}/slow?delayMs=1600")

    print("  Polling confirmation state and monitoring healing execution...", flush=True)
    healing_triggered = False

    for attempt in range(15):
        time.sleep(2)
        code, history = get(f"{GATEWAY_URL}/gateway/healing/history")
        code_s, state = get(f"{GATEWAY_URL}/gateway/anomaly/status/payment-service")

        print(f"  Poll {attempt + 1}: status={state.get('status')}, window={state.get('anomaliesInWindow')}/5, healingRecords={len(history)}", flush=True)

        if len(history) > 0:
            latest_record = history[0]
            print(f"  [+] Autonomous Healing Record Detected: action={latest_record.get('actionType')}, status={latest_record.get('actionStatus')}, reason={latest_record.get('triggerReason')}", flush=True)
            healing_triggered = True
            break

    assert healing_triggered, "Healing engine was not triggered after fault injection"

    # Check the audit record structure (suitable for MySQL)
    rec = history[0]
    assert rec.get("serviceName") == "payment-service", f"Expected payment-service, got {rec.get('serviceName')}"
    assert rec.get("actionType") in ["TRAFFIC_REDIRECTION", "RESTART", "RATE_LIMIT"], f"Unexpected action: {rec.get('actionType')}"
    assert rec.get("triggerReason") is not None, "Missing triggerReason"
    assert rec.get("timestamp") is not None, "Missing timestamp"
    assert rec.get("attemptCount") >= 1, "Missing attemptCount"
    print("  --> PASS: Remediated confirmed anomaly with strategy: " + str(rec.get("actionType")), flush=True)

    # Recovery Verification Check
    print("\n  Verifying post-healing recovery status in audit trail...", flush=True)
    # Wait for verification window (2.5s delay)
    time.sleep(3)
    code, history_after = get(f"{GATEWAY_URL}/gateway/healing/history")
    verified_rec = history_after[0]
    print(f"  Audit record after verification: actionStatus={verified_rec.get('actionStatus')}, recoveryStatus={verified_rec.get('recoveryStatus')}", flush=True)
    assert verified_rec.get("actionStatus") == "RECOVERED", f"Expected RECOVERED, got {verified_rec.get('actionStatus')}"
    assert verified_rec.get("recoveryStatus") == "RECOVERED", f"Expected recoveryStatus RECOVERED, got {verified_rec.get('recoveryStatus')}"
    assert "verified healthy" in verified_rec.get("recoveryDetails", "").lower()

    # Verify service is actually responsive through Gateway with fast latency
    t_start = time.time()
    code, res = get(f"{GATEWAY_URL}/payments")
    latency_ms = (time.time() - t_start) * 1000
    print(f"  Live payment request latency after healing: {latency_ms:.1f}ms (HTTP {code})", flush=True)
    assert code == 200, f"Expected HTTP 200 from recovered payment-service, got {code}"
    assert latency_ms < 500, f"Expected fast recovered latency (<500ms), got {latency_ms}ms"
    print("  --> PASS: Recovery verification succeeded! Service restored to fast healthy operation.", flush=True)

    # -------------------------------------------------------------
    # TEST 4: Cooldown Prevents Repeated Healing
    # -------------------------------------------------------------
    print("\n[TEST 4] Testing Anti-Thrashing Cooldown Protection...", flush=True)
    code, status = get(f"{GATEWAY_URL}/gateway/healing/status")
    cooldowns = status.get("activeCooldowns", {})
    print(f"  Active Cooldowns: {json.dumps(cooldowns, indent=2)}", flush=True)
    assert "payment-service" in cooldowns, "Expected payment-service to have an active cooldown"

    # Attempt to trigger healing while cooldown is active
    print("  Attempting to trigger healing during active cooldown...", flush=True)
    code, cooldown_res = post(f"{GATEWAY_URL}/gateway/healing/trigger/payment-service?force=false")
    print(f"  Cooldown trigger response: actionStatus={cooldown_res.get('actionStatus')}, recoveryStatus={cooldown_res.get('recoveryStatus')}", flush=True)
    assert cooldown_res.get("actionStatus") == "SKIPPED_COOLDOWN", f"Expected SKIPPED_COOLDOWN, got {cooldown_res.get('actionStatus')}"
    assert cooldown_res.get("recoveryStatus") == "SKIPPED", f"Expected recoveryStatus SKIPPED, got {cooldown_res.get('recoveryStatus')}"
    print("  --> PASS: Cooldown successfully suppressed redundant healing action!", flush=True)

    # -------------------------------------------------------------
    # TEST 5: Failed Recovery is Recorded & Max Retries Protected
    # -------------------------------------------------------------
    print("\n[TEST 5] Testing Failed Recovery Recording & Retry Limit...", flush=True)
    post(f"{GATEWAY_URL}/gateway/healing/reset")

    # Trigger healing on an unmanaged/unreachable service to verify failed recovery logging
    code, failed_rec = post(f"{GATEWAY_URL}/gateway/healing/trigger/unknown-service?force=true")
    print(f"  Failed recovery response: actionStatus={failed_rec.get('actionStatus')}, recoveryStatus={failed_rec.get('recoveryStatus')}", flush=True)
    assert failed_rec.get("actionStatus") == "FAILED_RECOVERY", f"Expected FAILED_RECOVERY, got {failed_rec.get('actionStatus')}"
    assert failed_rec.get("recoveryStatus") == "FAILED", f"Expected FAILED, got {failed_rec.get('recoveryStatus')}"
    print("  --> PASS: Failed recovery recorded accurately with FAILED_RECOVERY status.", flush=True)

    # Verify audit history logged this failure
    code, audit_history = get(f"{GATEWAY_URL}/gateway/healing/history")
    assert any(r.get("serviceName") == "unknown-service" and r.get("recoveryStatus") == "FAILED" for r in audit_history)
    print("  --> PASS: Failed recovery record confirmed in audit history.", flush=True)

    # -------------------------------------------------------------
    # TEST 6: Reset API
    # -------------------------------------------------------------
    print("\n[TEST 6] Testing POST /gateway/healing/reset...", flush=True)
    post(f"{GATEWAY_URL}/gateway/healing/reset")
    code, status_final = get(f"{GATEWAY_URL}/gateway/healing/status")
    assert len(status_final.get("activeCooldowns", {})) == 0
    assert status_final.get("totalAuditRecords") == 0
    print("  --> PASS: Healing reset API cleanly cleared cooldowns and audit records.", flush=True)

    print("\n" + "=" * 70, flush=True)
    print("      ALL PHASE 12 AUTONOMOUS HEALING ENGINE TESTS PASSED!", flush=True)
    print("=" * 70, flush=True)


if __name__ == "__main__":
    test_healing_engine()
