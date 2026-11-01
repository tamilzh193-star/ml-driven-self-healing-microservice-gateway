#!/usr/bin/env python3
"""
Chronological, Leak-Free Dataset Partitioning
=============================================
Splits dataset/telemetry_labeled.csv into three strictly disjoint, time-ordered sets:
  1. dataset/train_baseline.csv  : Pure healthy baseline telemetry (Chunk 1, Normal-only)
  2. dataset/calibration.csv     : Calibration set with normal + all fault types (Chunk 2)
  3. dataset/test_heldout.csv    : Untouched final evaluation test set (Chunk 3)

Guarantees:
  - 0 timestamp overlaps between Train, Calibration, and Test.
  - No random shuffling or leakage between operational time windows.
  - Preserves original dataset/telemetry.csv and dataset/telemetry_labeled.csv untouched.
"""

import os
import sys
import pandas as pd

DATASET_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_LABELED = os.path.join(DATASET_DIR, "telemetry_labeled.csv")

TRAIN_OUTPUT = os.path.join(DATASET_DIR, "train_baseline.csv")
CALIBRATION_OUTPUT = os.path.join(DATASET_DIR, "calibration.csv")
TEST_OUTPUT = os.path.join(DATASET_DIR, "test_heldout.csv")


def partition_datasets():
    if not os.path.exists(SOURCE_LABELED):
        print(f"[ERROR] Source labeled file missing: {SOURCE_LABELED}", flush=True)
        sys.exit(1)

    df = pd.read_csv(SOURCE_LABELED)
    total_len = len(df)
    print(f"[DATA] Loaded {total_len} labeled records from {SOURCE_LABELED}", flush=True)

    # Chronological partition:
    # Chunk 1 (0 to 920, ~60%): Early operational period
    # Chunk 2 (920 to 1228, ~20%): Mid period (Calibration)
    # Chunk 3 (1228 to 1536, ~20%): Late period (Final Held-Out Test)
    c1 = df.iloc[0:920].copy()
    c2 = df.iloc[920:1228].copy()
    c3 = df.iloc[1228:].copy()

    # Train: Pure Normal from Chunk 1
    train_df = c1[c1["scenario"] == "NORMAL"].copy()
    
    # Early chunk 1 faults for light contamination (3 per fault type = 12 samples out of 800, ~1.5% contamination)
    c1_faults = c1[c1["is_anomaly"] == 1]
    sampled_c1_faults = c1_faults.groupby("scenario").head(3)
    train_clean_df = pd.concat([train_df, sampled_c1_faults], ignore_index=True)

    cal_df = c2.copy()
    test_df = c3.copy()

    # Integrity Assertions: Strict disjointness
    s_train = set(train_clean_df["timestamp"])
    s_cal = set(cal_df["timestamp"])
    s_test = set(test_df["timestamp"])

    overlap_tc = s_train.intersection(s_cal)
    overlap_tt = s_train.intersection(s_test)
    overlap_ct = s_cal.intersection(s_test)

    if overlap_tc or overlap_tt or overlap_ct:
        raise ValueError(f"Partition leakage detected: TC={len(overlap_tc)}, TT={len(overlap_tt)}, CT={len(overlap_ct)}")

    # Save to disk
    TRAIN_CLEAN_OUTPUT = os.path.join(DATASET_DIR, "train_clean.csv")
    train_df.to_csv(TRAIN_OUTPUT, index=False)
    train_clean_df.to_csv(TRAIN_CLEAN_OUTPUT, index=False)
    cal_df.to_csv(CALIBRATION_OUTPUT, index=False)
    test_df.to_csv(TEST_OUTPUT, index=False)

    print("\n[SUCCESS] Datasets partitioned cleanly with ZERO overlap:")
    print(f"  - Training Baseline (Normal): {len(train_df):>4} records (100% NORMAL) -> {TRAIN_OUTPUT}")
    print(f"  - Training Clean (1.5% contam): {len(train_clean_df):>4} records -> {TRAIN_CLEAN_OUTPUT}")
    print(f"  - Calibration Set           : {len(cal_df):>4} records ({sum(cal_df['scenario']=='NORMAL')} Normal, {cal_df['is_anomaly'].sum()} Faults) -> {CALIBRATION_OUTPUT}")
    print(f"  - Held-out Test Set         : {len(test_df):>4} records ({sum(test_df['scenario']=='NORMAL')} Normal, {test_df['is_anomaly'].sum()} Faults) -> {TEST_OUTPUT}")
    print(f"  - Total Labeled Used        : {len(train_clean_df) + len(cal_df) + len(test_df)} records (120 discarded early-period fault records from Chunk 1)")
    print("\n[VERIFICATION] Timestamp intersections between all sets: EXACTLY 0.")


if __name__ == "__main__":
    partition_datasets()
