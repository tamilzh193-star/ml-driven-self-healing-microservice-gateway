#!/usr/bin/env python3
"""
Test Suite for LOFO Feature Attribution and Failure Signatures (Novelty N2)
===========================================================================
Validates:
1. Normal telemetry returns attribution shares and NORMAL_OPERATION.
2. Latency spike returns dominant_feature == 'latency' and LATENCY_SPIKE.
3. HTTP 500 error burst returns dominant_feature == 'error_rate' and ERROR_BURST.
4. CPU saturation returns dominant_feature == 'cpu' and CPU_SATURATION.
5. Memory exhaustion returns dominant_feature == 'memory' and MEMORY_LEAK.
6. Attribution shares sum to 1.0 (+/- float rounding).
"""

import sys
import unittest
import json
import os

# Add ml-service to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from app import app, FEATURE_COLUMNS


class TestLofoAttribution(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.client = app.test_client()

    def test_normal_telemetry(self):
        payload = {
            "service": "payment-service",
            "cpu": 0.5,
            "memory": 1.2,
            "latency": 2.5,
            "error_rate": 0.0,
            "request_rate": 0.2
        }
        resp = self.client.post("/predict", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("attribution", data)
        self.assertIn("dominant_feature", data)
        self.assertIn("signature", data)
        self.assertEqual(data["prediction"], "NORMAL")
        self.assertEqual(data["signature"], "NORMAL_OPERATION")
        self.assertAlmostEqual(sum(data["attribution"].values()), 1.0, places=2)

    def test_latency_spike_attribution(self):
        payload = {
            "service": "payment-service",
            "cpu": 0.5,
            "memory": 1.2,
            "latency": 1800.0,  # massive latency fault
            "error_rate": 0.0,
            "request_rate": 0.1
        }
        resp = self.client.post("/predict", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["prediction"], "ANOMALY")
        self.assertEqual(data["dominant_feature"], "latency")
        self.assertEqual(data["signature"], "LATENCY_SPIKE")
        self.assertGreater(data["attribution"]["latency"], 0.4)
        self.assertAlmostEqual(sum(data["attribution"].values()), 1.0, places=2)

    def test_error_burst_attribution(self):
        payload = {
            "service": "order-service",
            "cpu": 0.5,
            "memory": 1.2,
            "latency": 5.0,
            "error_rate": 80.0,  # severe 500 error rate
            "request_rate": 0.2
        }
        resp = self.client.post("/predict", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["prediction"], "ANOMALY")
        self.assertEqual(data["dominant_feature"], "error_rate")
        self.assertEqual(data["signature"], "ERROR_BURST")
        self.assertGreater(data["attribution"]["error_rate"], 0.4)
        self.assertAlmostEqual(sum(data["attribution"].values()), 1.0, places=2)

    def test_cpu_saturation_attribution(self):
        payload = {
            "service": "inventory-service",
            "cpu": 95.0,  # peg CPU
            "memory": 1.2,
            "latency": 3.0,
            "error_rate": 0.0,
            "request_rate": 0.2
        }
        resp = self.client.post("/predict", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["prediction"], "ANOMALY")
        self.assertEqual(data["dominant_feature"], "cpu")
        self.assertEqual(data["signature"], "CPU_SATURATION")
        self.assertGreater(data["attribution"]["cpu"], 0.4)
        self.assertAlmostEqual(sum(data["attribution"].values()), 1.0, places=2)

    def test_memory_leak_attribution(self):
        payload = {
            "service": "order-service",
            "cpu": 0.5,
            "memory": 92.0,  # exhausted heap
            "latency": 4.0,
            "error_rate": 0.0,
            "request_rate": 0.2
        }
        resp = self.client.post("/predict", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["prediction"], "ANOMALY")
        self.assertEqual(data["dominant_feature"], "memory")
        self.assertEqual(data["signature"], "MEMORY_LEAK")
        self.assertGreater(data["attribution"]["memory"], 0.4)
        self.assertAlmostEqual(sum(data["attribution"].values()), 1.0, places=2)


if __name__ == "__main__":
    unittest.main()
