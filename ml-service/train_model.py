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
    parser = argparse.ArgumentParser(description="Train Isolation Forest for Microservice Anomaly Detection (Leak-Free)")
    parser.add_argument("--data", type=str, default="dataset/train_clean.csv", help="Path to telemetry training dataset")
    parser.add_argument("--calibration-data", type=str, default="dataset/calibration.csv", help="Path to calibration dataset for threshold tuning")
    parser.add_argument("--test-data", type=str, default="dataset/test_heldout.csv", help="Path to untouched final test dataset")
    parser.add_argument("--output-dir", type=str, default="ml-service/model", help="Directory to save model artifacts")
    parser.add_argument("--n-estimators", type=int, default=100, help="Number of isolation trees in ensemble")
    parser.add_argument("--contamination", type=float, default=0.04, help="Expected proportion of anomalies in dataset")
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


def calibrate_decision_threshold(model, scaler, calibration_filepath, target_max_fpr=0.05):
    """
    Select decision threshold tau* exclusively using the calibration dataset.
    
    Constraint: FPR(tau) on normal calibration observations <= target_max_fpr (default 5.0%).
    Objective: Maximize anomaly Recall among eligible thresholds.
    Tie-breaking: Minimize FPR; if tied, take the median candidate to avoid boundary overfitting.
    """
    if not os.path.exists(calibration_filepath):
        raise FileNotFoundError(f"Calibration data missing: {calibration_filepath}. Cannot calibrate threshold.")

    df_cal = pd.read_csv(calibration_filepath)
    if "is_anomaly" not in df_cal.columns:
        raise ValueError(f"Calibration file {calibration_filepath} missing required ground-truth label 'is_anomaly'.")

    df_cal[FEATURE_COLUMNS] = df_cal[FEATURE_COLUMNS].apply(pd.to_numeric, errors="coerce")
    df_cal = df_cal.dropna(subset=FEATURE_COLUMNS + ["is_anomaly"]).copy()

    X_cal = df_cal[FEATURE_COLUMNS].values
    y_cal = df_cal["is_anomaly"].astype(int).values

    norm_count = int(np.sum(y_cal == 0))
    anom_count = int(np.sum(y_cal == 1))

    if norm_count == 0 or anom_count == 0:
        raise ValueError(f"Invalid calibration partition: requires both normal and anomaly samples. Got normal={norm_count}, anom={anom_count}.")

    X_cal_scaled = scaler.transform(X_cal)
    # score_samples returns anomaly scores (lower = more anomalous)
    scores = model.score_samples(X_cal_scaled)

    norm_scores = scores[y_cal == 0]
    anom_scores = scores[y_cal == 1]

    # Candidate thresholds: evaluate at every score boundary
    candidate_thresholds = np.sort(np.unique(scores))
    eligible = []

    for tau in candidate_thresholds:
        # Anomaly predicted if score < tau
        fpr = float(np.mean(norm_scores < tau))
        rec = float(np.mean(anom_scores < tau))
        if fpr <= target_max_fpr:
            eligible.append({
                "threshold": float(tau),
                "recall": rec,
                "fpr": fpr
            })

    if not eligible:
        min_observed_fpr = float(np.min([np.mean(norm_scores < tau) for tau in candidate_thresholds]))
        raise RuntimeError(
            f"Threshold calibration failed: No candidate threshold satisfied FPR <= {target_max_fpr * 100:.1f}%. "
            f"Minimum observed FPR was {min_observed_fpr * 100:.2f}%."
        )

    # Sort eligible by recall DESC, then fpr ASC
    max_recall = max(cand["recall"] for cand in eligible)
    top_candidates = [c for c in eligible if np.isclose(c["recall"], max_recall)]
    min_fpr = min(c["fpr"] for c in top_candidates)
    tied_candidates = [c for c in top_candidates if np.isclose(c["fpr"], min_fpr)]

    # If multiple thresholds have identical recall and fpr, select the median
    chosen_idx = len(tied_candidates) // 2
    selected_threshold = float(tied_candidates[chosen_idx]["threshold"])
    calibrated_fpr = float(tied_candidates[chosen_idx]["fpr"])
    calibrated_recall = float(tied_candidates[chosen_idx]["recall"])

    print("\n" + "=" * 65, flush=True)
    print("      ISOLATION FOREST THRESHOLD CALIBRATION (CALIBRATION ONLY)", flush=True)
    print("=" * 65, flush=True)
    print(f"  Target Constraint     : FPR <= {target_max_fpr * 100:.1f}% on normal calibration observations", flush=True)
    print(f"  Calibration Samples   : {len(df_cal)} (Normal={norm_count}, Anomaly={anom_count})", flush=True)
    print(f"  Selected Threshold    : {selected_threshold:.6f}", flush=True)
    print(f"  Calibrated FPR        : {calibrated_fpr * 100:.2f}%", flush=True)
    print(f"  Calibrated Recall     : {calibrated_recall * 100:.2f}%", flush=True)
    print("=" * 65, flush=True)

    return selected_threshold, {
        "target_max_fpr": target_max_fpr,
        "selected_threshold": selected_threshold,
        "calibration_fpr": round(calibrated_fpr, 4),
        "calibration_recall": round(calibrated_recall, 4),
        "total_calibration_records": len(df_cal),
        "normal_records": norm_count,
        "anomaly_records": anom_count,
        "selection_method": "constrained_recall_maximization_fpr_le_0.05"
    }


def evaluate_model(scaler, model, labeled_filepath, decision_threshold=None):
    """Evaluate Isolation Forest predictions using the calibrated decision threshold."""
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

    # Compute raw anomaly score
    scores = model.score_samples(X_eval_scaled)

    # Classify based on decision_threshold if provided, else model.offset_
    if decision_threshold is not None:
        y_pred = np.where(scores < decision_threshold, 1, 0)
    else:
        raw_preds = model.predict(X_eval_scaled)
        y_pred = np.where(raw_preds == -1, 1, 0)

    # Compute metrics
    acc = float(accuracy_score(y_true, y_pred))
    prec = float(precision_score(y_true, y_pred, zero_division=0))
    rec = float(recall_score(y_true, y_pred, zero_division=0))
    f1 = float(f1_score(y_true, y_pred, zero_division=0))
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred).ravel()
    fpr = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0

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
        "false_positive_rate": round(fpr, 4),
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
    print(f"  Threshold Used    : {decision_threshold if decision_threshold is not None else getattr(model, 'offset_', 'default')}", flush=True)
    print(f"  Accuracy          : {acc * 100:.2f}%", flush=True)
    print(f"  Precision         : {prec * 100:.2f}%", flush=True)
    print(f"  Recall            : {rec * 100:.2f}%", flush=True)
    print(f"  False Positive Rate: {fpr * 100:.2f}%", flush=True)
    print(f"  F1-Score          : {f1 * 100:.2f}%", flush=True)
    print(f"\n  Confusion Matrix  :", flush=True)
    print(f"    True Normal  (TN) : {tn:>5}    False Alarm (FP) : {fp:>5}", flush=True)
    print(f"    Missed Fault (FN) : {fn:>5}    True Anomaly(TP) : {tp:>5}", flush=True)
    print("=" * 65, flush=True)

    return metrics


def save_artifacts(output_dir, scaler, model, args, decision_threshold, threshold_metadata, eval_metrics, cal_metrics=None, feature_medians=None, scaled_feature_medians=None, data_integrity=None):
    """Serialize fitted model, preprocessor, and metadata including baseline medians and calibrated threshold."""
    os.makedirs(output_dir, exist_ok=True)

    model_path = os.path.join(output_dir, "isolation_forest.joblib")
    scaler_path = os.path.join(output_dir, "scaler.joblib")
    meta_path = os.path.join(output_dir, "model_metadata.json")

    joblib.dump(model, model_path)
    joblib.dump(scaler, scaler_path)

    metadata = {
        "algorithm": "IsolationForest",
        "framework": "scikit-learn",
        "model_version": "2.1-calibrated",
        "decision_threshold": float(decision_threshold),
        "threshold_selection_protocol": "calibration_set_constrained_recall_maximization_fpr_le_0.05",
        "threshold_metadata": threshold_metadata or {},
        "features": FEATURE_COLUMNS,
        "feature_medians": feature_medians or {},
        "scaled_feature_medians": scaled_feature_medians or {},
        "parameters": {
            "n_estimators": args.n_estimators,
            "contamination": args.contamination,
            "random_state": args.random_state
        },
        "training_timestamp": datetime.now().isoformat(),
        "data_integrity": data_integrity or {},
        "calibration_metrics": cal_metrics or {},
        "evaluation_metrics": eval_metrics
    }

    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    print(f"\n[ARTIFACTS] Saved model artifacts to '{output_dir}':", flush=True)
    print(f"  - Model:             {model_path} ({os.path.getsize(model_path)} bytes)", flush=True)
    print(f"  - Scaler:            {scaler_path} ({os.path.getsize(scaler_path)} bytes)", flush=True)
    print(f"  - Decision Threshold: {decision_threshold:.6f}", flush=True)
    print(f"  - Metadata:          {meta_path}", flush=True)


def main():
    args = parse_args()
    print("=" * 65, flush=True)
    print("   ML-Driven Self-Healing Microservice Gateway - Phase 8", flush=True)
    print("   Isolation Forest Training with Constrained Threshold Calibration", flush=True)
    print("=" * 65, flush=True)

    # 1. Load training data
    clean_df, X = load_and_preprocess(args.data)

    # Verify zero overlap between datasets if calibration and test files exist
    data_integrity = {"training_records": len(clean_df), "data_leakage": "0.0% (Zero shared timestamps)"}
    if os.path.exists(args.calibration_data) and os.path.exists(args.test_data):
        df_cal = pd.read_csv(args.calibration_data)
        df_test = pd.read_csv(args.test_data)
        s_tr = set(clean_df.get("timestamp", []))
        s_cal = set(df_cal.get("timestamp", []))
        s_test = set(df_test.get("timestamp", []))

        overlap_tc = len(s_tr.intersection(s_cal))
        overlap_tt = len(s_tr.intersection(s_test))
        overlap_ct = len(s_cal.intersection(s_test))

        print(f"[DATA INTEGRITY] Verifying partition independence:")
        print(f"  - Train vs Calibration timestamp overlap : {overlap_tc}")
        print(f"  - Train vs Held-out Test timestamp overlap: {overlap_tt}")
        print(f"  - Calibration vs Test timestamp overlap  : {overlap_ct}")
        if overlap_tc > 0 or overlap_tt > 0 or overlap_ct > 0:
            raise ValueError("Severe Data Leakage Detected: Datasets are not disjoint!")
        data_integrity["calibration_records"] = len(df_cal)
        data_integrity["test_records"] = len(df_test)
        data_integrity["disjoint_verified"] = True

    # 2. Train model strictly on training data
    scaler, model, X_scaled = train_model(
        X,
        n_estimators=args.n_estimators,
        contamination=args.contamination,
        random_state=args.random_state
    )

    # 3. Compute baseline medians for LOFO attribution
    feature_medians = {col: float(np.median(clean_df[col].values)) for col in FEATURE_COLUMNS}
    scaled_feature_medians = {col: float(np.median(X_scaled[:, i])) for i, col in enumerate(FEATURE_COLUMNS)}

    # 4. Calibrate decision threshold ONLY on calibration data (targeting FPR <= 5%)
    if not os.path.exists(args.calibration_data):
        raise FileNotFoundError(f"Calibration data missing: {args.calibration_data}. Calibration is required.")

    decision_threshold, threshold_metadata = calibrate_decision_threshold(
        model, scaler, args.calibration_data, target_max_fpr=0.05
    )

    # Evaluate on Calibration Set using the newly selected threshold
    print("\n" + "-" * 65, flush=True)
    print("  STAGE 1: CALIBRATION SET PERFORMANCE AT SELECTED THRESHOLD", flush=True)
    print("-" * 65, flush=True)
    cal_metrics = evaluate_model(scaler, model, args.calibration_data, decision_threshold=decision_threshold)

    # 5. Evaluate on Untouched Final Test Set (held-out test set evaluated ONLY after threshold is fixed)
    if not os.path.exists(args.test_data):
        raise FileNotFoundError(f"Held-out test data missing: {args.test_data}.")

    print("\n" + "-" * 65, flush=True)
    print("  STAGE 2: UNTOUCHED FINAL TEST SET EVALUATION", flush=True)
    print("-" * 65, flush=True)
    eval_metrics = evaluate_model(scaler, model, args.test_data, decision_threshold=decision_threshold)

    # 6. Save artifacts
    save_artifacts(
        args.output_dir, scaler, model, args,
        decision_threshold=decision_threshold,
        threshold_metadata=threshold_metadata,
        eval_metrics=eval_metrics,
        cal_metrics=cal_metrics,
        feature_medians=feature_medians,
        scaled_feature_medians=scaled_feature_medians,
        data_integrity=data_integrity
    )

    print("\n[SUCCESS] Model training and threshold calibration finished successfully.", flush=True)


if __name__ == "__main__":
    main()
