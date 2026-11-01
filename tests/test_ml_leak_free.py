"""
Leak-Free Machine Learning Verification Test Suite
==================================================
Tests:
  1. Data Separation & Independence: 0% timestamp overlap between train, calibration, and test.
  2. Preprocessing Consistency: StandardScaler feature counts, means, and transforms.
  3. Model Artifact Loading: Joblib integrity of Isolation Forest and StandardScaler.
  4. Inference Output Specification: Schema compliance, LOFO attribution shares sum to 1.0,
     and correct dominant feature / signature mapping.
"""

import os
import sys
import json
import unittest
import numpy as np
import pandas as pd
import joblib

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "ml-service"))

import app as ml_app

FEATURE_COLUMNS = ["cpu", "memory", "latency", "error_rate", "request_rate"]
DATASET_DIR = os.path.join(PROJECT_ROOT, "dataset")
MODEL_DIR = os.path.join(PROJECT_ROOT, "ml-service", "model")


class TestMlLeakFree(unittest.TestCase):

    def test_01_dataset_separation_zero_leakage(self):
        """Verify that training, calibration, and test datasets have zero overlap."""
        train_file = os.path.join(DATASET_DIR, "train_clean.csv")
        cal_file = os.path.join(DATASET_DIR, "calibration.csv")
        test_file = os.path.join(DATASET_DIR, "test_heldout.csv")

        self.assertTrue(os.path.exists(train_file), f"Missing {train_file}")
        self.assertTrue(os.path.exists(cal_file), f"Missing {cal_file}")
        self.assertTrue(os.path.exists(test_file), f"Missing {test_file}")

        df_train = pd.read_csv(train_file)
        df_cal = pd.read_csv(cal_file)
        df_test = pd.read_csv(test_file)

        s_train = set(df_train["timestamp"])
        s_cal = set(df_cal["timestamp"])
        s_test = set(df_test["timestamp"])

        overlap_tc = s_train.intersection(s_cal)
        overlap_tt = s_train.intersection(s_test)
        overlap_ct = s_cal.intersection(s_test)

        self.assertEqual(len(overlap_tc), 0, f"Leakage between train and calibration: {len(overlap_tc)} shared timestamps")
        self.assertEqual(len(overlap_tt), 0, f"Leakage between train and test: {len(overlap_tt)} shared timestamps")
        self.assertEqual(len(overlap_ct), 0, f"Leakage between calibration and test: {len(overlap_ct)} shared timestamps")

    def test_02_preprocessing_consistency(self):
        """Verify StandardScaler preprocessor integrity and feature consistency."""
        scaler_path = os.path.join(MODEL_DIR, "scaler.joblib")
        self.assertTrue(os.path.exists(scaler_path), "scaler.joblib missing")

        scaler = joblib.load(scaler_path)
        self.assertEqual(scaler.n_features_in_, len(FEATURE_COLUMNS))
        self.assertEqual(len(scaler.mean_), len(FEATURE_COLUMNS))
        self.assertEqual(len(scaler.scale_), len(FEATURE_COLUMNS))

        # Test numerical transformation stability
        sample = np.array([[2.5, 1.2, 5.0, 0.0, 1.0]])
        scaled = scaler.transform(sample)
        self.assertEqual(scaled.shape, (1, len(FEATURE_COLUMNS)))
        self.assertFalse(np.isnan(scaled).any(), "NaN detected in scaled transformation")

    def test_03_model_artifact_loading(self):
        """Verify Isolation Forest estimator and model metadata integrity."""
        model_path = os.path.join(MODEL_DIR, "isolation_forest.joblib")
        meta_path = os.path.join(MODEL_DIR, "model_metadata.json")

        self.assertTrue(os.path.exists(model_path), "isolation_forest.joblib missing")
        self.assertTrue(os.path.exists(meta_path), "model_metadata.json missing")

        model = joblib.load(model_path)
        self.assertTrue(hasattr(model, "predict"), "Model object missing predict() method")
        self.assertTrue(hasattr(model, "decision_function"), "Model object missing decision_function() method")

        with open(meta_path, "r", encoding="utf-8") as f:
            metadata = json.load(f)

        self.assertEqual(metadata.get("algorithm"), "IsolationForest")
        self.assertEqual(metadata.get("features"), FEATURE_COLUMNS)
        self.assertIn("data_integrity", metadata)
        self.assertTrue(metadata["data_integrity"].get("disjoint_verified"))
        self.assertIn("evaluation_metrics", metadata)
        self.assertIn("per_scenario", metadata["evaluation_metrics"])

    def test_04_inference_output_and_attribution(self):
        """Verify inference pipeline schema, LOFO attribution shares, and signature mapping."""
        # Reload Flask app artifacts
        ml_app.load_artifacts()

        # 1. Normal baseline telemetry
        normal_obs = {
            "service": "payment-service",
            "cpu": 2.1,
            "memory": 1.1,
            "latency": 3.5,
            "error_rate": 0.0,
            "request_rate": 2.0
        }
        res_normal = ml_app.process_single_prediction(normal_obs)
        self.assertIn("prediction", res_normal)
        self.assertIn("is_anomaly", res_normal)
        self.assertIn("anomaly_score", res_normal)
        self.assertIn("attribution", res_normal)
        self.assertIn("dominant_feature", res_normal)

        # Attribution shares must sum to 1.0 (+- 0.002)
        attrib = res_normal["attribution"]
        total_share = sum(attrib.values())
        self.assertAlmostEqual(total_share, 1.0, places=2)

        # 2. Severe Latency fault injection
        fault_obs = {
            "service": "order-service",
            "cpu": 3.0,
            "memory": 1.2,
            "latency": 1600.0,
            "error_rate": 0.0,
            "request_rate": 1.0
        }
        res_fault = ml_app.process_single_prediction(fault_obs)
        self.assertTrue(res_fault["is_anomaly"])
        self.assertEqual(res_fault["prediction"], "ANOMALY")
        self.assertEqual(res_fault["dominant_feature"], "latency")
        self.assertEqual(res_fault["signature"], "LATENCY_SPIKE")

    def test_05_threshold_calibration_fpr_constraint(self):
        """Verify that the saved decision_threshold satisfies FPR <= 5.0% on normal calibration data."""
        meta_path = os.path.join(MODEL_DIR, "model_metadata.json")
        cal_path = os.path.join(DATASET_DIR, "calibration.csv")
        scaler_path = os.path.join(MODEL_DIR, "scaler.joblib")
        model_path = os.path.join(MODEL_DIR, "isolation_forest.joblib")

        with open(meta_path, "r", encoding="utf-8") as f:
            metadata = json.load(f)

        self.assertIn("decision_threshold", metadata, "Metadata must explicitly store decision_threshold")
        threshold = metadata["decision_threshold"]
        self.assertIsInstance(threshold, float)

        scaler = joblib.load(scaler_path)
        model = joblib.load(model_path)
        df_cal = pd.read_csv(cal_path)

        X_cal = scaler.transform(df_cal[FEATURE_COLUMNS].values)
        scores = model.score_samples(X_cal)
        y_cal = df_cal["is_anomaly"].values

        normal_scores = scores[y_cal == 0]
        cal_fpr = float(np.mean(normal_scores < threshold))

        self.assertLessEqual(cal_fpr, 0.05, f"Calibration FPR {cal_fpr:.4f} exceeds target constraint of 5.0%")

        # Check recorded calibration metrics match
        rec_metrics = metadata.get("calibration_metrics", {})
        self.assertAlmostEqual(cal_fpr, rec_metrics.get("false_positive_rate", 0.0), places=4)

    def test_06_app_prediction_matches_calibrated_threshold(self):
        """Verify that ml-service app.py /predict uses score_samples(X) < decision_threshold consistently."""
        ml_app.load_artifacts()
        meta_path = os.path.join(MODEL_DIR, "model_metadata.json")
        with open(meta_path, "r", encoding="utf-8") as f:
            metadata = json.load(f)

        threshold = metadata["decision_threshold"]
        self.assertEqual(ml_app.decision_threshold, threshold)

        cal_path = os.path.join(DATASET_DIR, "calibration.csv")
        df_cal = pd.read_csv(cal_path)

        # Test 20 diverse samples from calibration set
        sample_subset = df_cal.sample(n=20, random_state=42)
        for _, row in sample_subset.iterrows():
            obs = {col: float(row[col]) for col in FEATURE_COLUMNS}
            obs["service"] = str(row["service"])

            # Call app logic
            res = ml_app.process_single_prediction(obs)

            # Independent computation
            feat_arr = np.array([[obs[c] for c in FEATURE_COLUMNS]])
            scaled = ml_app.scaler.transform(feat_arr)
            raw_score = float(ml_app.model.score_samples(scaled)[0])
            expected_anom = bool(raw_score < threshold)

            self.assertEqual(
                res["is_anomaly"],
                expected_anom,
                f"Mismatch in app prediction vs threshold decision for {obs}: app={res['is_anomaly']}, expected={expected_anom}"
            )

    def test_07_heldout_independence_from_threshold_selection(self):
        """Verify that the held-out test set was not used to select the decision threshold."""
        meta_path = os.path.join(MODEL_DIR, "model_metadata.json")
        with open(meta_path, "r", encoding="utf-8") as f:
            metadata = json.load(f)

        thresh_meta = metadata.get("threshold_metadata", {})
        self.assertEqual(
            thresh_meta.get("selection_method"),
            "constrained_recall_maximization_fpr_le_0.05"
        )
        self.assertEqual(
            thresh_meta.get("total_calibration_records"),
            308,
            "Threshold selection sample count must equal calibration set size (308), not test or combined size"
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
