#!/usr/bin/env python3
"""
=============================================================================
Phase 18 Master Verification Suite: Final MCA Dissertation Project Sign-Off
=============================================================================
Executes end-to-end multi-tier verification covering all 18 project phases:
  Pillar 1: Multi-Tier Microservice Routing & Load Dispatching
  Pillar 2: Real-Time Telemetry Pipeline & Actuator Aggregation
  Pillar 3: Machine Learning Service (Isolation Forest)
  Pillar 4: Anti-Flapping Sliding-Window Anomaly Confirmation (K/5)
  Pillar 5: Autonomous Healing Engine & Multi-Strategy Remediation
  Pillar 6: Anti-Thrashing Cooldown & Max Retry Safeguards
  Pillar 7: Active Health Probing & Post-Remediation Verification
  Pillar 8: Relational Persistence Layer (Telemetry, Anomalies, Audit Logs)
  Pillar 9: Thymeleaf Operations Center & Real-Time Dashboard API
  Pillar 10: Packaging, Production Artifacts & Standalone Orchestration
=============================================================================
"""

import os
import sys
import json
import time
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

GATEWAY_URL = "http://localhost:8080"
ML_URL = "http://localhost:5000"
ORDER_URL = "http://localhost:8081"
PAYMENT_URL = "http://localhost:8082"
INVENTORY_URL = "http://localhost:8083"

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def http_get(url, timeout=15.0):
    req = Request(url, headers={"Accept": "application/json"})
    with urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode())

def http_post(url, data=None, timeout=20.0):
    body = json.dumps(data).encode("utf-8") if data else b""
    headers = {"Accept": "application/json"}
    if data:
        headers["Content-Type"] = "application/json"
    req = Request(url, data=body, headers=headers, method="POST")
    try:
        with urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode()
            try:
                return resp.status, json.loads(text)
            except Exception:
                return resp.status, text
    except HTTPError as e:
        text = e.read().decode() if e.fp else ""
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text

class TestPhase18MasterVerification(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        print("\n" + "=" * 80)
        print("  PHASE 18: MASTER DISSERTATION FULL-SYSTEM VERIFICATION")
        print("=" * 80)

    # -------------------------------------------------------------
    # Pillar 1: Multi-Tier Microservice Routing
    # -------------------------------------------------------------
    def test_pillar_01_microservice_routing(self):
        print("\n[PILLAR 1] Verifying Gateway Microservice Routing...")
        status, orders = http_get(f"{GATEWAY_URL}/orders")
        self.assertEqual(status, 200)
        self.assertIsInstance(orders, list)

        status, payments = http_get(f"{GATEWAY_URL}/payments")
        self.assertEqual(status, 200)
        self.assertIsInstance(payments, list)

        status, inventory = http_get(f"{GATEWAY_URL}/inventory")
        self.assertEqual(status, 200)
        self.assertIsInstance(inventory, list)
        print("  --> PASS: All downstream microservice routes accessible via Gateway.")

    # -------------------------------------------------------------
    # Pillar 2: Real-Time Telemetry Pipeline
    # -------------------------------------------------------------
    def test_pillar_02_telemetry_pipeline(self):
        print("\n[PILLAR 2] Verifying Real-Time Telemetry Pipeline...")
        status, latest = http_get(f"{GATEWAY_URL}/gateway/telemetry/latest")
        self.assertEqual(status, 200)
        for svc in ["order-service", "payment-service", "inventory-service"]:
            self.assertIn(svc, latest)
            telemetry = latest[svc]
            self.assertIn("cpu", telemetry)
            self.assertIn("memory", telemetry)
            self.assertIn("latency", telemetry)
            self.assertIn("errorRate", telemetry)
            self.assertEqual(telemetry["healthStatus"], "UP")
        print("  --> PASS: Multivariate telemetry stream aggregated across all services.")

    # -------------------------------------------------------------
    # Pillar 3: Machine Learning Service (Isolation Forest)
    # -------------------------------------------------------------
    def test_pillar_03_machine_learning_service(self):
        print("\n[PILLAR 3] Verifying Isolation Forest ML Service...")
        status, health = http_get(f"{ML_URL}/health")
        self.assertEqual(status, 200)
        self.assertEqual(health.get("status"), "UP")
        self.assertTrue(health.get("model_loaded"))

        # Healthy vector inference (calibrated to training distribution)
        normal_vec = {"service": "payment-service", "cpu": 3.47, "memory": 0.99, "latency": 2.54, "error_rate": 0.0, "request_rate": 2.06}
        status, res = http_post(f"{ML_URL}/predict", normal_vec)
        self.assertEqual(status, 200)
        self.assertEqual(res.get("prediction"), "NORMAL")
        self.assertFalse(res.get("is_anomaly"))

        # Anomalous vector inference
        anomaly_vec = {"service": "payment-service", "cpu": 85.0, "memory": 9.0, "latency": 1500.0, "error_rate": 0.9, "request_rate": 200.0}
        status, res = http_post(f"{ML_URL}/predict", anomaly_vec)
        self.assertEqual(status, 200)
        self.assertEqual(res.get("prediction"), "ANOMALY")
        self.assertTrue(res.get("is_anomaly"))
        print("  --> PASS: Isolation Forest correctly infers both normal and anomalous feature vectors.")

    # -------------------------------------------------------------
    # Pillar 4: Anti-Flapping Sliding-Window State Machine
    # -------------------------------------------------------------
    def test_pillar_04_anomaly_confirmation_state_machine(self):
        print("\n[PILLAR 4] Verifying Sliding-Window Anomaly Confirmation State Machine...")
        status, anomaly_states = http_get(f"{GATEWAY_URL}/gateway/anomaly/status")
        self.assertEqual(status, 200)
        self.assertIsInstance(anomaly_states, dict)
        for svc in ["order-service", "payment-service", "inventory-service"]:
            self.assertIn(svc, anomaly_states)
            state = anomaly_states[svc]
            self.assertIn(state["status"], ["HEALTHY", "SUSPECT", "CONFIRMED_ANOMALY", "RECOVERING"])
            self.assertEqual(state["windowSize"], 5)
            self.assertLessEqual(state["consecutiveAnomalies"], 5)
        print("  --> PASS: Anti-flapping sliding window hysteresis operating cleanly.")

    # -------------------------------------------------------------
    # Pillar 5 & 6: Autonomous Healing & Anti-Thrashing Cooldown
    # -------------------------------------------------------------
    def test_pillar_05_autonomous_healing_and_cooldown(self):
        print("\n[PILLAR 5 & 6] Verifying Autonomous Healing Engine & Cooldown Protection...")
        # Reset healing state
        http_post(f"{GATEWAY_URL}/gateway/healing/reset")

        # Trigger controlled healing action on inventory-service
        status, heal_rec = http_post(f"{GATEWAY_URL}/gateway/healing/trigger/inventory-service?force=true", timeout=15.0)
        self.assertEqual(status, 200)
        self.assertIn(heal_rec.get("actionStatus"), ["SUCCESS", "RECOVERED", "SKIPPED_COOLDOWN"])

        # Immediate re-trigger must trigger Anti-Thrashing Cooldown (SKIPPED_COOLDOWN)
        status, cooldown_rec = http_post(f"{GATEWAY_URL}/gateway/healing/trigger/inventory-service?force=false", timeout=10.0)
        self.assertEqual(status, 200)
        self.assertEqual(cooldown_rec.get("actionStatus"), "SKIPPED_COOLDOWN")
        self.assertEqual(cooldown_rec.get("recoveryStatus"), "SKIPPED")
        print("  --> PASS: Autonomous remediation executed and cooldown suppression active.")

    # -------------------------------------------------------------
    # Pillar 7: Active Health Probing & Post-Remediation Verification
    # -------------------------------------------------------------
    def test_pillar_07_recovery_verification(self):
        print("\n[PILLAR 7] Verifying Post-Remediation Health Probing...")
        status, history = http_get(f"{GATEWAY_URL}/gateway/healing/history")
        self.assertEqual(status, 200)
        self.assertIsInstance(history, list)
        self.assertGreater(len(history), 0, "Expected at least 1 healing record in audit history")

        latest = history[-1]
        self.assertIn("serviceName", latest)
        self.assertIn("actionType", latest)
        self.assertIn("actionStatus", latest)
        self.assertIn("recoveryStatus", latest)
        print(f"  --> PASS: Latest healing action verified: {latest['actionType']} -> status={latest['recoveryStatus']}")

    # -------------------------------------------------------------
    # Pillar 8: Relational Persistence Layer
    # -------------------------------------------------------------
    def test_pillar_08_relational_persistence(self):
        print("\n[PILLAR 8] Verifying Relational Persistence Layer (MySQL/H2)...")
        status, db_status = http_get(f"{GATEWAY_URL}/gateway/db/status")
        self.assertEqual(status, 200)
        self.assertEqual(db_status.get("databaseStatus"), "CONNECTED")
        self.assertTrue(db_status.get("persistenceEnabled"))
        self.assertIn("telemetryRowCount", db_status)
        self.assertIn("anomalyRowCount", db_status)
        self.assertIn("healingAuditRowCount", db_status)
        print(f"  --> PASS: Database connected ({db_status.get('databaseProductName')}), persistent tables verified.")

    # -------------------------------------------------------------
    # Pillar 9: Operations Center Dashboard & Live Stats API
    # -------------------------------------------------------------
    def test_pillar_09_operations_center_dashboard(self):
        print("\n[PILLAR 9] Verifying Thymeleaf Operations Center & Live Stats API...")
        time.sleep(1.0)
        # Verify live JSON aggregator
        status, stats = http_get(f"{GATEWAY_URL}/api/dashboard/stats", timeout=15.0)
        self.assertEqual(status, 200)
        self.assertIn("services", stats)
        self.assertIn("infrastructure", stats)
        self.assertIn("healingEngine", stats)

        # Verify HTML rendering
        req = Request(f"{GATEWAY_URL}/dashboard")
        with urlopen(req, timeout=15.0) as resp:
            self.assertEqual(resp.status, 200)
            html = resp.read().decode()
            self.assertIn("Autonomous Self-Healing Cloud Operations Center", html)
            self.assertIn("pollDashboard", html)
        print("  --> PASS: Operations Center renders HTML5 UI and supplies live aggregator JSON.")

    # -------------------------------------------------------------
    # Pillar 10: Packaging, Production Artifacts & Standalone Orchestration
    # -------------------------------------------------------------
    def test_pillar_10_packaging_and_orchestration(self):
        print("\n[PILLAR 10] Verifying Packaging, Fat JARs & Standalone Orchestration...")
        for svc in ["gateway", "order-service", "payment-service", "inventory-service"]:
            jar_path = os.path.join(PROJECT_ROOT, svc, "target", f"{svc}-1.0.0.jar")
            self.assertTrue(os.path.exists(jar_path), f"Missing Fat JAR: {jar_path}")

        for script in ["run_all.bat", "stop_all.bat", "run_all.sh", "stop_all.sh", "check_health.bat"]:
            self.assertTrue(os.path.exists(os.path.join(PROJECT_ROOT, script)), f"Missing script: {script}")

        self.assertTrue(os.path.exists(os.path.join(PROJECT_ROOT, "scripts", "verify_cluster.py")))
        print("  --> PASS: All standalone production Fat JARs, scripts, and utilities present.")

if __name__ == "__main__":
    unittest.main()
