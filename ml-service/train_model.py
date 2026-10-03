#!/usr/bin/env python3
"""
Isolation Forest Anomaly Detection Training Pipeline
===================================================
MCA Dissertation: ML-Driven Self-Healing Microservice Gateway
Phase 8: Isolation Forest Training

This script trains an unsupervised Isolation Forest model on the microservice
telemetry dataset (dataset/telemetry.csv) and evaluates its performance against
the ground-truth labeled evaluation dataset (dataset/telemetry_labeled.csv).

Outputs:
  - ml-service/model/isolation_forest.joblib : Fitted Isolation Forest estimator
  - ml-service/model/scaler.joblib           : Fitted StandardScaler preprocessor
  - ml-service/model/model_metadata.json     : Model metadata and evaluation metrics
"""

import os
import sys
import json
import argparse
from datetime import datetime

import numpy as np
import pandas as pd
import joblib
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import (
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    confusion_matrix,
    classification_report
)

FEATURE_COLUMNS = ["cpu", "memory", "latency", "error_rate", "request_rate"]


def parse_args():
    parser = argparse.ArgumentParser(description="Train Isolation Forest for Microservice Anomaly Detection")
    parser.add_argument("--data", type=str, default="dataset/telemetry.csv", help="Path to telemetry training dataset")
    parser.add_argument("--labeled-data", type=str, default="dataset/telemetry_labeled.csv", help="Path to labeled evaluation dataset")
    parser.add_argument("--output-dir", type=str, default="ml-service/model", help="Directory to save model artifacts")
    parser.add_argument("--n-estimators", type=int, default=100, help="Number of isolation trees in ensemble")
    parser.add_argument("--contamination", type=float, default=0.14, help="Expected proportion of anomalies in dataset")
    parser.add_argument("--random-state", type=int, default=42, help="Seed for reproducibility")
    return parser.parse_args()


def load_and_preprocess(filepath):
    """Load telemetry dataset, validate columns, and extract features."""
    if not os.path.exists(filepath):
        print(f"[ERROR] Training data file not found: {filepath}", flush=True)
        sys.exit(1)

    df = pd.read_csv(filepath)
    print(f"[DATA] Loaded {len(df)} records from {filepath}", flush=True)

    # Verify required feature columns
    for col in FEATURE_COLUMNS:
        if col not in df.columns:
            print(f"[ERROR] Required feature '{col}' missing from dataset.", flush=True)
            sys.exit(1)

    # Clean numeric features
    df[FEATURE_COLUMNS] = df[FEATURE_COLUMNS].apply(pd.to_numeric, errors="coerce")
    clean_df = df.dropna(subset=FEATURE_COLUMNS).copy()

    dropped = len(df) - len(clean_df)
    if dropped > 0:
        print(f"[CLEAN] Dropped {dropped} records with invalid/null values.", flush=True)

    X = clean_df[FEATURE_COLUMNS].values
    return clean_df, X


def train_model(X, n_estimators=100, contamination=0.14, random_state=42):
    """Fit StandardScaler and IsolationForest on telemetry features."""
    print(f"\n[TRAIN] Fitting StandardScaler on {len(X)} observations...", flush=True)
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)

    print(f"[TRAIN] Fitting IsolationForest (n_estimators={n_estimators}, contamination={contamination}, random_state={random_state})...", flush=True)
    model = IsolationForest(
        n_estimators=n_estimators,
        contamination=contamination,
        random_state=random_state,
        n_jobs=-1
    )
    model.fit(X_scaled)
    print("[TRAIN] Model training completed successfully.", flush=True)
    return scaler, model, X_scaled


def evaluate_model(scaler, model, labeled_filepath):
    """Evaluate Isolation Forest predictions against ground-truth labeled anomalies."""
    if not os.path.exists(labeled_filepath):
        print(f"[WARN] Labeled evaluation file not found: {labeled_filepath}. Skipping evaluation.", flush=True)
        return None

    df_eval = pd.read_csv(labeled_filepath)
    print(f"\n[EVAL] Evaluating model on {len(df_eval)} ground-truth records from {labeled_filepath}...", flush=True)

    df_eval[FEATURE_COLUMNS] = df_eval[FEATURE_COLUMNS].apply(pd.to_numeric, errors="coerce")
    df_eval = df_eval.dropna(subset=FEATURE_COLUMNS + ["is_anomaly"]).copy()

    X_eval = df_eval[FEATURE_COLUMNS].values
    y_true = df_eval["is_anomaly"].astype(int).values

    # Scale features using the training scaler
    X_eval_scaled = scaler.transform(X_eval)

    # Isolation Forest predict: 1 for inlier (normal), -1 for outlier (anomaly)
    raw_preds = model.predict(X_eval_scaled)
    y_pred = np.where(raw_preds == -1, 1, 0)

    # Anomaly scores (lower score = more anomalous)
    anomaly_scores = model.decision_function(X_eval_scaled)

    # Compute metrics
    acc = float(accuracy_score(y_true, y_pred))
    prec = float(precision_score(y_true, y_pred, zero_division=0))
    rec = float(recall_score(y_true, y_pred, zero_division=0))
    f1 = float(f1_score(y_true, y_pred, zero_division=0))
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred).ravel()

    # Per-scenario breakdown if available
    scenario_metrics = {}
    if "scenario" in df_eval.columns:
        print("\n--- Per-Scenario Detection Breakdown ---", flush=True)
        for sc in df_eval["scenario"].unique():
            mask = df_eval["scenario"] == sc
            sc_true = y_true[mask]
            sc_pred = y_pred[mask]
            sc_total = len(sc_true)
            sc_detected = int(np.sum(sc_pred == 1))
            detection_rate = (sc_detected / sc_total) * 100 if sc_total > 0 else 0.0
            scenario_metrics[sc] = {
                "total_records": int(sc_total),
                "flagged_as_anomaly": sc_detected,
                "detection_rate_pct": round(detection_rate, 2)
            }
            label_type = "ANOMALY" if (len(sc_true) > 0 and sc_true[0] == 1) else "NORMAL"
            print(f"  [{label_type:<7}] Scenario: {sc:<16} | Total: {sc_total:>4} | Flagged Anomaly: {sc_detected:>4} ({detection_rate:.1f}%)", flush=True)

    metrics = {
        "accuracy": round(acc, 4),
        "precision": round(prec, 4),
        "recall": round(rec, 4),
        "f1_score": round(f1, 4),
        "confusion_matrix": {
            "true_negatives": int(tn),
            "false_positives": int(fp),
            "false_negatives": int(fn),
            "true_positives": int(tp)
        },
        "per_scenario": scenario_metrics
    }

    print("\n" + "=" * 65, flush=True)
    print("             ISOLATION FOREST EVALUATION RESULTS", flush=True)
    print("=" * 65, flush=True)
    print(f"  Accuracy          : {acc * 100:.2f}%", flush=True)
    print(f"  Precision         : {prec * 100:.2f}%", flush=True)
    print(f"  Recall            : {rec * 100:.2f}%", flush=True)
    print(f"  F1-Score          : {f1 * 100:.2f}%", flush=True)
    print(f"\n  Confusion Matrix  :", flush=True)
    print(f"    True Normal  (TN) : {tn:>5}    False Alarm (FP) : {fp:>5}", flush=True)
    print(f"    Missed Fault (FN) : {fn:>5}    True Anomaly(TP) : {tp:>5}", flush=True)
    print("=" * 65, flush=True)

    return metrics


def save_artifacts(output_dir, scaler, model, args, eval_metrics):
    """Serialize fitted model, preprocessor, and metadata."""
    os.makedirs(output_dir, exist_ok=True)

    model_path = os.path.join(output_dir, "isolation_forest.joblib")
    scaler_path = os.path.join(output_dir, "scaler.joblib")
    meta_path = os.path.join(output_dir, "model_metadata.json")

    joblib.dump(model, model_path)
    joblib.dump(scaler, scaler_path)

    metadata = {
        "algorithm": "IsolationForest",
        "framework": "scikit-learn",
        "features": FEATURE_COLUMNS,
        "parameters": {
            "n_estimators": args.n_estimators,
            "contamination": args.contamination,
            "random_state": args.random_state
        },
        "training_timestamp": datetime.now().isoformat(),
        "evaluation_metrics": eval_metrics
    }

    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    print(f"\n[ARTIFACTS] Saved model artifacts to '{output_dir}':", flush=True)
    print(f"  - Model:    {model_path} ({os.path.getsize(model_path)} bytes)", flush=True)
    print(f"  - Scaler:   {scaler_path} ({os.path.getsize(scaler_path)} bytes)", flush=True)
    print(f"  - Metadata: {meta_path}", flush=True)


def main():
    args = parse_args()
    print("=" * 65, flush=True)
    print("   ML-Driven Self-Healing Microservice Gateway - Phase 8", flush=True)
    print("            Isolation Forest Model Training", flush=True)
    print("=" * 65, flush=True)

    # 1. Load data
    _, X = load_and_preprocess(args.data)

    # 2. Train model
    scaler, model, _ = train_model(
        X,
        n_estimators=args.n_estimators,
        contamination=args.contamination,
        random_state=args.random_state
    )

    # 3. Evaluate against labeled ground truth
    eval_metrics = evaluate_model(scaler, model, args.labeled_data)

    # 4. Save artifacts
    save_artifacts(args.output_dir, scaler, model, args, eval_metrics)

    print("\n[SUCCESS] Phase 8 model training and evaluation finished successfully.", flush=True)


if __name__ == "__main__":
    main()
