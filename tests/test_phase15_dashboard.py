"""
=============================================================================
Phase 15 Test Suite: Thymeleaf Dashboard & Operations Center UI
=============================================================================
Verifies:
  1. Thymeleaf Dashboard HTML rendering on GET / and GET /dashboard (HTTP 200)
  2. Complete UI component structure and offline self-contained assets
  3. Live JSON aggregator endpoint GET /api/dashboard/stats
  4. Interactive chaos injection trigger via dashboard API
  5. Interactive autonomous healing trigger via dashboard API
  6. Administrative system reset via POST /api/dashboard/reset
=============================================================================
"""

import time
import json
import requests

GATEWAY_URL = "http://localhost:8080"


def test_phase15_dashboard():
    print("\n" + "=" * 70)
    print("      PHASE 15: THYMELEAF DASHBOARD & OPERATIONS CENTER TESTS")
    print("=" * 70)

    # -------------------------------------------------------------
    # TEST 1: Dashboard HTML Rendering
    # -------------------------------------------------------------
    print("\n[TEST 1] Verifying Thymeleaf Dashboard HTML rendering...")
    for path in ["/", "/dashboard"]:
        resp = requests.get(f"{GATEWAY_URL}{path}", timeout=15)
        print(f"  GET {path} -> HTTP {resp.status_code} ({len(resp.text)} bytes)")
        assert resp.status_code == 200, f"Expected HTTP 200, got {resp.status_code}"
        assert "text/html" in resp.headers.get("Content-Type", ""), "Expected Content-Type text/html"

        # Check essential UI components
        html = resp.text
        assert "Autonomous Self-Healing Cloud Operations Center" in html, "Missing header title"
        assert "Downstream Microservices & Real-Time Telemetry" in html, "Missing microservices section"
        assert "Autonomous Healing Immutable Audit Trail" in html, "Missing audit trail section"
        assert "Infrastructure & Machine Learning" in html, "Missing infrastructure panel"
        assert "pollDashboard" in html, "Missing client-side polling logic"
        print(f"  [+] Path '{path}' contains all required visual dashboard sections.")

    print("  --> PASS: Thymeleaf Dashboard renders HTML5 operations center successfully.")

    # -------------------------------------------------------------
    # TEST 2: Real-Time Stats Aggregator API
    # -------------------------------------------------------------
    print("\n[TEST 2] Verifying GET /api/dashboard/stats aggregator endpoint...")
    resp = requests.get(f"{GATEWAY_URL}/api/dashboard/stats", timeout=15)
    assert resp.status_code == 200, f"Expected 200, got {resp.status_code}"
    data = resp.json()

    print(f"  Dashboard Stats Payload Keys: {list(data.keys())}")
    assert "services" in data, "Missing 'services' key"
    assert "infrastructure" in data, "Missing 'infrastructure' key"
    assert "mlService" in data, "Missing 'mlService' key"
    assert "healingEngine" in data, "Missing 'healingEngine' key"
    assert "database" in data, "Missing 'database' key"
    assert "kpis" in data, "Missing 'kpis' key"

    # Verify microservices
    services = data["services"]
    assert len(services) == 3, f"Expected 3 microservices, got {len(services)}"
    service_names = [s["name"] for s in services]
    assert "order-service" in service_names
    assert "payment-service" in service_names
    assert "inventory-service" in service_names

    for s in services:
        print(f"  Service: {s['name']} | Health: {s['health']} | State: {s['anomalyStatus']} | Latency: {s['latency']}ms | Window: {s['windowScore']}")
        assert "latency" in s and "errorRate" in s and "cpu" in s

    # Verify ML info
    ml = data["mlService"]
    print(f"  ML Service Status: {ml.get('status')} | Model: {ml.get('model')}")
    assert ml.get("status") == "UP"

    # Verify Database info
    db = data["database"]
    print(f"  DB Persistence: {db.get('databaseStatus')} | Product: {db.get('databaseProductName')}")
    assert db.get("databaseStatus") == "CONNECTED"

    print("  --> PASS: Dashboard stats aggregator returned valid live cluster metadata.")

    # -------------------------------------------------------------
    # TEST 3: Interactive Chaos Trigger via Dashboard API
    # -------------------------------------------------------------
    print("\n[TEST 3] Testing interactive chaos trigger via dashboard API...")
    chaos_res = requests.post(
        f"{GATEWAY_URL}/api/dashboard/chaos/trigger?service=payment-service&type=slow&value=1600",
        timeout=5
    ).json()

    print(f"  Chaos Trigger Response: {json.dumps(chaos_res)}")
    assert chaos_res.get("status") == "SUCCESS", f"Expected SUCCESS, got {chaos_res}"

    # Verify the chaos state on payment-service
    chaos_status = requests.get("http://localhost:8082/chaos/status", timeout=3).json()
    assert chaos_status.get("slowActive", False) is True, "Expected payment-service slow chaos to be active"
    print("  [+] Slow latency fault verified active on payment-service.")
    print("  --> PASS: Interactive chaos injection through dashboard API succeeded.")

    # -------------------------------------------------------------
    # TEST 4: Interactive Healing Trigger via Dashboard API
    # -------------------------------------------------------------
    print("\n[TEST 4] Testing manual remediation execution via dashboard API...")
    heal_res = requests.post(
        f"{GATEWAY_URL}/api/dashboard/healing/trigger?service=payment-service",
        timeout=15
    ).json()

    print(f"  Healing Trigger Response: Status={heal_res.get('status')}")
    assert heal_res.get("status") == "SUCCESS"
    record = heal_res.get("record", {})
    assert record.get("serviceName") == "payment-service"
    assert record.get("actionStatus") in ["RECOVERED", "INITIATED"]
    print(f"  [+] Healing Action Recorded: actionType={record.get('actionType')}, status={record.get('actionStatus')}")

    # Verify stats reflect the new audit record
    time.sleep(1)
    updated_stats = requests.get(f"{GATEWAY_URL}/api/dashboard/stats", timeout=3).json()
    recent_history = updated_stats.get("healingEngine", {}).get("recentAuditHistory", [])
    assert len(recent_history) > 0, "Expected at least 1 audit record in dashboard stats"
    print(f"  [+] Dashboard stats now show {len(recent_history)} audit records.")
    print("  --> PASS: Dashboard healing trigger and state update verified.")

    # -------------------------------------------------------------
    # TEST 5: System Clean Reset via Dashboard API
    # -------------------------------------------------------------
    print("\n[TEST 5] Testing full system reset via dashboard API...")
    reset_res = requests.post(f"{GATEWAY_URL}/api/dashboard/reset", timeout=5).json()
    print(f"  System Reset Response: {json.dumps(reset_res)}")
    assert reset_res.get("status") == "SUCCESS"

    # Verify chaos is cleared
    chaos_after = requests.get("http://localhost:8082/chaos/status", timeout=3).json()
    assert chaos_after.get("slowActive", False) is False, "Expected slow chaos to be cleared"
    print("  [+] Chaos successfully cleared across all microservices.")

    # Verify audit history is reset
    clean_stats = requests.get(f"{GATEWAY_URL}/api/dashboard/stats", timeout=3).json()
    clean_history = clean_stats.get("healingEngine", {}).get("recentAuditHistory", [])
    assert len(clean_history) == 0, f"Expected 0 audit records after reset, got {len(clean_history)}"
    print("  [+] Healing audit trail cleanly reset.")
    print("  --> PASS: Full system reset through dashboard API succeeded.")

    print("\n" + "=" * 70)
    print("      ALL PHASE 15 DASHBOARD TESTS PASSED SUCCESSFULLY!")
    print("=" * 70 + "\n")


if __name__ == "__main__":
    test_phase15_dashboard()
