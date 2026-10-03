#!/usr/bin/env python3
"""
Flask Machine Learning Anomaly Detection REST Service
====================================================
MCA Dissertation: ML-Driven Self-Healing Microservice Gateway
Phase 9: Flask ML REST API

Serves the trained Isolation Forest model on port 5000 for real-time
telemetry anomaly inference requested by the Spring Cloud Gateway.

Endpoints:
  - GET  /health      : Health status and model load verification
  - GET  /model/info  : Model parameters, features, and evaluation metrics
  - POST /predict     : Evaluates telemetry for anomaly detection (single or batch)
"""

import os
import sys
import json
from datetime import datetime

import numpy as np
import joblib
from flask import Flask, request, jsonify

app = Flask(__name__)

FEATURE_COLUMNS = ["cpu", "memory", "latency", "error_rate", "request_rate"]

# Model and scaler storage
MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model")
MODEL_PATH = os.path.join(MODEL_DIR, "isolation_forest.joblib")
SCALER_PATH = os.path.join(MODEL_DIR, "scaler.joblib")
METADATA_PATH = os.path.join(MODEL_DIR, "model_metadata.json")

model = None
scaler = None
metadata = {}


def load_artifacts():
    """Load model, scaler, and metadata once on startup."""
    global model, scaler, metadata
    try:
        if not os.path.exists(MODEL_PATH) or not os.path.exists(SCALER_PATH):
            raise FileNotFoundError(f"Model artifacts missing in {MODEL_DIR}. Please run train_model.py first.")

        model = joblib.load(MODEL_PATH)
        scaler = joblib.load(SCALER_PATH)

        if os.path.exists(METADATA_PATH):
            with open(METADATA_PATH, "r", encoding="utf-8") as f:
                metadata = json.load(f)
        else:
            metadata = {"algorithm": "IsolationForest", "status": "No metadata file found"}

        print(f"[STARTUP] Successfully loaded Isolation Forest model and StandardScaler from '{MODEL_DIR}'.", flush=True)
    except Exception as e:
        print(f"[STARTUP ERROR] Failed to load model artifacts: {e}", file=sys.stderr, flush=True)


# Load artifacts when module loads
load_artifacts()


@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint confirming service status and model readiness."""
    is_ready = model is not None and scaler is not None
    status_code = 200 if is_ready else 503
    return jsonify({
        "status": "UP" if is_ready else "DOWN",
        "service": "ml-service",
        "model_loaded": model is not None,
        "scaler_loaded": scaler is not None,
        "timestamp": datetime.now().isoformat()
    }), status_code


@app.route("/model/info", methods=["GET"])
def model_info():
    """Return model architecture, parameters, and validation metrics."""
    if not metadata:
        return jsonify({"error": "Model metadata not available"}), 503
    return jsonify(metadata), 200


def process_single_prediction(data):
    """Validate, preprocess, and evaluate a single telemetry observation."""
    if not isinstance(data, dict):
        raise ValueError("Each telemetry observation must be a JSON object.")

    service_name = data.get("service") or data.get("serviceName") or "unknown-service"

    # Extract and validate features
    features = {}
    for col in FEATURE_COLUMNS:
        # Also support camelCase equivalents if passed from Java
        camel_col = "".join(w.capitalize() if i > 0 else w for i, w in enumerate(col.split("_")))
        val = data.get(col) if col in data else data.get(camel_col)

        if val is None:
            raise ValueError(f"Missing required feature: '{col}' (or '{camel_col}')")

        try:
            features[col] = float(val)
        except (ValueError, TypeError):
            raise ValueError(f"Feature '{col}' must be a valid numeric value, got: '{val}'")

    feature_array = np.array([[features[c] for c in FEATURE_COLUMNS]])

    # Preprocess with fitted scaler
    scaled_array = scaler.transform(feature_array)

    # Predict: 1 = normal, -1 = anomaly
    pred_val = int(model.predict(scaled_array)[0])
    is_anomaly = pred_val == -1
    prediction_label = "ANOMALY" if is_anomaly else "NORMAL"

    # Continuous decision score: negative indicates anomaly, positive indicates normal
    anomaly_score = float(model.decision_function(scaled_array)[0])

    return {
        "service": service_name,
        "prediction": prediction_label,
        "is_anomaly": is_anomaly,
        "anomaly_score": round(anomaly_score, 4),
        "timestamp": data.get("timestamp") or datetime.now().isoformat(),
        "features": {col: round(features[col], 2) for col in FEATURE_COLUMNS}
    }


@app.route("/predict", methods=["POST"])
def predict():
    """
    Ingest telemetry metrics and return anomaly predictions.
    Supports either a single telemetry JSON object or an array of objects.
    """
    if model is None or scaler is None:
        return jsonify({"error": "Model not loaded. Service unavailable."}), 503

    payload = request.get_json(silent=True)
    if payload is None:
        return jsonify({"error": "Invalid or missing JSON payload."}), 400

    try:
        if isinstance(payload, list):
            # Batch prediction
            results = [process_single_prediction(item) for item in payload]
            return jsonify(results), 200
        elif isinstance(payload, dict):
            # Single prediction
            result = process_single_prediction(payload)
            return jsonify(result), 200
        else:
            return jsonify({"error": "Request payload must be a JSON object or array."}), 400
    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": f"Internal prediction failure: {str(e)}"}), 500


if __name__ == "__main__":
    print("[STARTING] Flask ML Service starting on port 5000...", flush=True)
    app.run(host="0.0.0.0", port=5000, debug=False)
