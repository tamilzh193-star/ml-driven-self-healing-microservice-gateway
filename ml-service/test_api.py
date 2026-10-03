#!/usr/bin/env python3
"""
Test Suite for Flask ML REST API
================================
Validates all endpoints of ml-service/app.py on port 5000.
"""

import sys
import json
import urllib.request
import urllib.error

BASE_URL = "http://localhost:5000"


def request(endpoint, method="GET", body=None):
    """Execute an HTTP request and return (status, parsed_json_or_text)."""
    url = f"{BASE_URL}{endpoint}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            text = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(text)
            except json.JSONDecodeError:
                return resp.status, text
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8") if e.fp else ""
        try:
            return e.code, json.loads(text)
        except json.JSONDecodeError:
            return e.code, text
    except Exception as e:
        return 0, str(e)


def run_tests():
    passed = 0
    total = 0

    print("=" * 65)
    print("        FLASK ML REST API VERIFICATION SUITE")
    print("=" * 65)

    # Test 1: Health Endpoint
    total += 1
    print("\n[TEST 1] GET /health")
    code, res = request("/health")
    print(f"  Status: {code}, Response: {res}")
    assert code == 200, f"Expected 200, got {code}"
    assert res.get("status") == "UP", "Service not UP"
    assert res.get("model_loaded") is True, "Model not loaded"
    assert res.get("scaler_loaded") is True, "Scaler not loaded"
    print("  --> PASS: Health endpoint is UP and model is ready.")
    passed += 1

    # Test 2: Model Info Endpoint
    total += 1
    print("\n[TEST 2] GET /model/info")
    code, res = request("/model/info")
    print(f"  Status: {code}")
    assert code == 200, f"Expected 200, got {code}"
    assert res.get("algorithm") == "IsolationForest", "Algorithm mismatch"
    assert "cpu" in res.get("features", []), "Feature schema mismatch"
    print(f"  Features: {res.get('features')}")
    print(f"  Evaluation Metrics: {res.get('evaluation_metrics')}")
    print("  --> PASS: Model metadata successfully returned.")
    passed += 1

    # Test 3: Predict - Normal Telemetry
    total += 1
    print("\n[TEST 3] POST /predict (Normal Telemetry)")
    normal_payload = {
        "service": "order-service",
        "cpu": 2.15,
        "memory": 1.45,
        "latency": 3.20,
        "error_rate": 0.0,
        "request_rate": 2.50
    }
    code, res = request("/predict", method="POST", body=normal_payload)
    print(f"  Status: {code}")
    print(f"  Prediction: {res.get('prediction')}, Score: {res.get('anomaly_score')}")
    assert code == 200, f"Expected 200, got {code}"
    assert res.get("prediction") == "NORMAL", f"Expected NORMAL, got {res.get('prediction')}"
    assert res.get("is_anomaly") is False, "Expected is_anomaly=False"
    assert res.get("anomaly_score") > 0, "Normal score should be positive"
    print("  --> PASS: Normal telemetry correctly classified as NORMAL.")
    passed += 1

    # Test 4: Predict - Slow Latency Anomaly
    total += 1
    print("\n[TEST 4] POST /predict (Latency Anomaly)")
    slow_payload = {
        "service": "payment-service",
        "cpu": 1.80,
        "memory": 1.30,
        "latency": 1520.40,
        "error_rate": 0.0,
        "request_rate": 1.10
    }
    code, res = request("/predict", method="POST", body=slow_payload)
    print(f"  Status: {code}")
    print(f"  Prediction: {res.get('prediction')}, Score: {res.get('anomaly_score')}")
    assert code == 200, f"Expected 200, got {code}"
    assert res.get("prediction") == "ANOMALY", f"Expected ANOMALY, got {res.get('prediction')}"
    assert res.get("is_anomaly") is True, "Expected is_anomaly=True"
    assert res.get("anomaly_score") < 0, "Anomaly score should be negative"
    print("  --> PASS: Slow latency fault correctly classified as ANOMALY.")
    passed += 1

    # Test 5: Predict - HTTP 500 Error Surge Anomaly
    total += 1
    print("\n[TEST 5] POST /predict (HTTP 500 Error Anomaly)")
    error_payload = {
        "service": "inventory-service",
        "cpu": 3.50,
        "memory": 1.50,
        "latency": 4.10,
        "error_rate": 100.0,
        "request_rate": 0.0
    }
    code, res = request("/predict", method="POST", body=error_payload)
    print(f"  Status: {code}")
    print(f"  Prediction: {res.get('prediction')}, Score: {res.get('anomaly_score')}")
    assert code == 200, f"Expected 200, got {code}"
    assert res.get("prediction") == "ANOMALY", f"Expected ANOMALY, got {res.get('prediction')}"
    assert res.get("is_anomaly") is True, "Expected is_anomaly=True"
    assert res.get("anomaly_score") < 0, "Anomaly score should be negative"
    print("  --> PASS: 500 error surge correctly classified as ANOMALY.")
    passed += 1

    # Test 6: Predict - Batch Payload
    total += 1
    print("\n[TEST 6] POST /predict (Batch Array Payload)")
    batch_payload = [
        {"service": "order-service", "cpu": 1.5, "memory": 1.1, "latency": 2.0, "error_rate": 0.0, "request_rate": 1.5},
        {"service": "payment-service", "cpu": 1.2, "memory": 1.3, "latency": 1515.0, "error_rate": 0.0, "request_rate": 1.0},
        {"service": "inventory-service", "cpu": 2.0, "memory": 1.2, "latency": 2.5, "error_rate": 0.0, "request_rate": 1.2}
    ]
    code, res = request("/predict", method="POST", body=batch_payload)
    print(f"  Status: {code}, Items returned: {len(res) if isinstance(res, list) else 0}")
    assert code == 200, f"Expected 200, got {code}"
    assert isinstance(res, list), "Expected list output for batch request"
    assert len(res) == 3, "Expected 3 prediction results"
    assert res[0]["prediction"] == "NORMAL", "Order should be NORMAL"
    assert res[1]["prediction"] == "ANOMALY", "Payment should be ANOMALY"
    assert res[2]["prediction"] == "NORMAL", "Inventory should be NORMAL"
    print("  --> PASS: Batch payload predictions matched expectations.")
    passed += 1

    # Test 7: Error Handling - Missing Feature
    total += 1
    print("\n[TEST 7] POST /predict (Missing Feature -> HTTP 400)")
    bad_payload = {"service": "order-service", "cpu": 2.0}
    code, res = request("/predict", method="POST", body=bad_payload)
    print(f"  Status: {code}, Response: {res}")
    assert code == 400, f"Expected 400, got {code}"
    assert "error" in res, "Error field missing in bad request response"
    print("  --> PASS: Missing features gracefully rejected with HTTP 400.")
    passed += 1

    print("\n" + "=" * 65)
    print(f"  VERIFICATION RESULT: {passed}/{total} TESTS PASSED")
    print("=" * 65)


if __name__ == "__main__":
    run_tests()
