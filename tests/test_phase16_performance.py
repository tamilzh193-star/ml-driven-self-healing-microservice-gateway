#!/usr/bin/env python3
"""
Phase 16 Automated Test Suite:
1. Validates Postman collection and environment JSON structure, schema, and API requests.
2. Validates Apache JMeter JMX test plan XML schema, thread groups, samplers, and assertions.
3. Executes concurrent load test simulation measuring latency, throughput, and error boundaries.
"""

import os
import sys
import json
import xml.etree.ElementTree as ET
import unittest
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

# Import load test runner from tests/jmeter/run_load_test.py
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "jmeter"))
import run_load_test

GATEWAY_BASE = "http://localhost:8080"

class TestPhase16PerformanceSuite(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Verify Gateway availability
        try:
            req = Request(f"{GATEWAY_BASE}/actuator/health", headers={"Accept": "application/json"})
            with urlopen(req, timeout=5.0) as resp:
                cls.gateway_online = (resp.status == 200)
        except Exception as e:
            cls.gateway_online = False
            print(f"[WARN] Gateway not accessible: {e}")

    # ==========================================================
    # 1. Postman Collection & Environment Validation
    # ==========================================================
    def test_01_postman_files_exist_and_valid_json(self):
        col_path = os.path.join(os.path.dirname(__file__), "postman", "self_healing_cloud_postman_collection.json")
        env_path = os.path.join(os.path.dirname(__file__), "postman", "self_healing_cloud_environment.json")

        self.assertTrue(os.path.exists(col_path), f"Postman collection missing at {col_path}")
        self.assertTrue(os.path.exists(env_path), f"Postman environment missing at {env_path}")

        with open(col_path, "r", encoding="utf-8") as f:
            collection = json.load(f)
        with open(env_path, "r", encoding="utf-8") as f:
            environment = json.load(f)

        self.assertIn("info", collection)
        self.assertIn("schema", collection["info"])
        self.assertIn("collection/v2.1.0", collection["info"]["schema"])

        # Check environment variables
        env_keys = {item["key"]: item["value"] for item in environment.get("values", [])}
        self.assertIn("gateway_url", env_keys)
        self.assertIn("ml_url", env_keys)
        self.assertIn("order_url", env_keys)
        self.assertIn("payment_url", env_keys)
        self.assertIn("inventory_url", env_keys)

    def test_02_postman_collection_contains_all_key_folders(self):
        col_path = os.path.join(os.path.dirname(__file__), "postman", "self_healing_cloud_postman_collection.json")
        with open(col_path, "r", encoding="utf-8") as f:
            collection = json.load(f)

        folder_names = [item["name"] for item in collection.get("item", [])]
        expected_folders = [
            "1. Microservice Business Routes",
            "2. Gateway Health & Telemetry",
            "3. Machine Learning Service",
            "4. Anomaly Confirmation State Machine",
            "5. Autonomous Healing Engine",
            "6. Relational Persistence Layer",
            "7. Chaos Injection & Resilience Testing",
            "8. Operations Center Dashboard API"
        ]

        for exp in expected_folders:
            self.assertTrue(any(exp in name for name in folder_names), f"Expected folder '{exp}' not found in {folder_names}")

    def test_03_execute_representative_postman_endpoints(self):
        if not self.gateway_online:
            self.skipTest("Gateway daemon is offline")

        # Test business route through gateway (/orders)
        req = Request(f"{GATEWAY_BASE}/orders")
        with urlopen(req, timeout=5.0) as resp:
            self.assertEqual(resp.status, 200)

        # Test telemetry latest endpoint
        req = Request(f"{GATEWAY_BASE}/gateway/telemetry/latest")
        with urlopen(req, timeout=5.0) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode())
            self.assertIsInstance(data, dict)

        # Test anomaly status
        req = Request(f"{GATEWAY_BASE}/gateway/anomaly/status")
        with urlopen(req, timeout=5.0) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode())
            self.assertIsInstance(data, (dict, list))

        # Test dashboard API
        req = Request(f"{GATEWAY_BASE}/api/dashboard/stats")
        with urlopen(req, timeout=5.0) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode())
            self.assertIn("services", data)

    # ==========================================================
    # 2. Apache JMeter Test Plan Validation
    # ==========================================================
    def test_04_jmeter_jmx_xml_structure(self):
        jmx_path = os.path.join(os.path.dirname(__file__), "jmeter", "self_healing_load_test.jmx")
        self.assertTrue(os.path.exists(jmx_path), f"JMeter JMX missing at {jmx_path}")

        tree = ET.parse(jmx_path)
        root = tree.getroot()
        self.assertEqual(root.tag, "jmeterTestPlan")

        # Verify TestPlan element
        test_plans = root.findall(".//TestPlan")
        self.assertTrue(len(test_plans) >= 1)

        # Verify ThreadGroups
        thread_groups = root.findall(".//ThreadGroup")
        tg_names = [tg.get("testname", "") for tg in thread_groups]
        self.assertTrue(len(thread_groups) >= 3, f"Expected at least 3 ThreadGroups, found {len(thread_groups)}")
        self.assertTrue(any("Baseline" in name for name in tg_names))
        self.assertTrue(any("Surge" in name for name in tg_names))
        self.assertTrue(any("Healing" in name or "Fault" in name for name in tg_names))

        # Verify HTTPSamplerProxy elements
        samplers = root.findall(".//HTTPSamplerProxy")
        self.assertTrue(len(samplers) >= 5, f"Expected at least 5 samplers, found {len(samplers)}")

        # Verify ResponseAssertion elements
        assertions = root.findall(".//ResponseAssertion")
        self.assertTrue(len(assertions) >= 3, f"Expected response assertions in JMX plan, found {len(assertions)}")

        # Verify ResultCollector listeners
        collectors = root.findall(".//ResultCollector")
        self.assertTrue(len(collectors) >= 1, f"Expected summary listeners in JMX plan, found {len(collectors)}")

    # ==========================================================
    # 3. Performance & Load Benchmark Execution
    # ==========================================================
    def test_05_run_baseline_load_simulation(self):
        if not self.gateway_online:
            self.skipTest("Gateway daemon is offline")

        endpoints = [
            {"method": "GET", "path": "/orders"},
            {"method": "GET", "path": "/inventory"},
            {"method": "GET", "path": "/payments"}
        ]
        metrics = run_load_test.run_stage(
            stage_name="Unit Test - Baseline Load",
            concurrency=5,
            requests_per_thread=10,
            endpoints=endpoints
        )

        self.assertEqual(metrics["total_requests"], 50)
        self.assertEqual(metrics["failed_requests"], 0, "Expected zero failures during baseline load")
        self.assertGreater(metrics["throughput_tps"], 20.0, "Expected throughput > 20 req/sec")
        self.assertLess(metrics["avg_latency_ms"], 200.0, "Expected average latency < 200 ms")

    def test_06_run_surge_stress_load_simulation(self):
        if not self.gateway_online:
            self.skipTest("Gateway daemon is offline")

        surge_endpoints = [
            {"method": "GET", "path": "/orders"},
            {"method": "POST", "path": "/orders", "body": {"customerName": "TestRunner", "item": "Desk", "quantity": 2, "price": 199.99}},
            {"method": "GET", "path": "/inventory"},
            {"method": "GET", "path": "/payments"}
        ]
        metrics = run_load_test.run_stage(
            stage_name="Unit Test - Surge Stress Load",
            concurrency=15,
            requests_per_thread=10,
            endpoints=surge_endpoints
        )

        self.assertEqual(metrics["total_requests"], 150)
        self.assertLessEqual(metrics["error_rate_pct"], 5.0, "Error rate should remain under 5% under surge load")
        self.assertGreater(metrics["throughput_tps"], 30.0, "Expected throughput > 30 req/sec")

    def test_07_verify_telemetry_health_post_load(self):
        if not self.gateway_online:
            self.skipTest("Gateway daemon is offline")

        req = Request(f"{GATEWAY_BASE}/gateway/telemetry/latest")
        with urlopen(req, timeout=5.0) as resp:
            data = json.loads(resp.read().decode())
            for svc in ["order-service", "payment-service", "inventory-service"]:
                self.assertIn(svc, data)
                metrics = data[svc]
                self.assertEqual(metrics.get("healthStatus"), "UP")
                self.assertIsNotNone(metrics.get("cpu"))

if __name__ == "__main__":
    unittest.main()
