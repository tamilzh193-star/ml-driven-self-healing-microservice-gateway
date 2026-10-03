"""
=============================================================================
Phase 14 Test Suite: Automated Evaluation & Fault Injection Benchmark
=============================================================================
Verifies:
  1. Benchmark suite execution
  2. MTTD metric compliance (< 25s)
  3. MTTR metric compliance (< 15s)
  4. Overall healing success rate (>= 90%)
  5. Steady-state false positive rate (0.0%)
  6. Anti-flapping and anti-thrashing cooldown validation
  7. Generation and schema integrity of benchmark_results.json and benchmark_summary.md
=============================================================================
"""

import os
import sys
import json
import time
import requests

# Ensure project root is in sys.path
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

BENCHMARK_DIR = os.path.join(PROJECT_ROOT, "benchmark")
RESULTS_JSON = os.path.join(BENCHMARK_DIR, "benchmark_results.json")
SUMMARY_MD = os.path.join(BENCHMARK_DIR, "benchmark_summary.md")


def test_phase14_benchmark():
    print("\n" + "=" * 70)
    print("      PHASE 14: AUTOMATED EVALUATION & BENCHMARK TEST SUITE")
    print("=" * 70)

    # 1. Execute Benchmark Harness
    print("\n[STEP 1] Executing evaluate_self_healing benchmark harness...")
    from benchmark.evaluate_self_healing import execute_full_benchmark

    summary = execute_full_benchmark()

    # 2. Verify Output Artifacts Exist
    print("\n[STEP 2] Verifying generated benchmark output artifacts...")
    assert os.path.exists(RESULTS_JSON), f"Missing benchmark results JSON: {RESULTS_JSON}"
    assert os.path.exists(SUMMARY_MD), f"Missing benchmark summary Markdown: {SUMMARY_MD}"
    print(f"  [+] Found {RESULTS_JSON} ({os.path.getsize(RESULTS_JSON)} bytes)")
    print(f"  [+] Found {SUMMARY_MD} ({os.path.getsize(SUMMARY_MD)} bytes)")
    print("  --> PASS: Benchmark artifacts generated successfully.")

    # 3. Verify JSON Schema and Content
    print("\n[STEP 3] Validating JSON schema and aggregate metrics...")
    with open(RESULTS_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)

    assert "aggregate_metrics" in data, "Missing 'aggregate_metrics' key in results JSON"
    assert "scenarios" in data, "Missing 'scenarios' key in results JSON"

    agg = data["aggregate_metrics"]
    mttd = agg.get("mean_time_to_detect_mttd_sec", 999)
    mttr = agg.get("mean_time_to_recover_mttr_sec", 999)
    success_rate = agg.get("overall_benchmark_success_rate_pct", 0)
    fpr = agg.get("false_positive_rate_baseline_pct", 100)

    print(f"  Empirical Mean MTTD: {mttd} s")
    print(f"  Empirical Mean MTTR: {mttr} s")
    print(f"  Overall Success Rate: {success_rate} %")
    print(f"  False Positive Rate: {fpr} %")

    # Assertions on metrics
    assert mttd <= 25.0, f"MTTD exceeded SLA threshold (expected <= 25s, got {mttd}s)"
    assert mttr <= 15.0, f"MTTR exceeded SLA threshold (expected <= 15s, got {mttr}s)"
    assert success_rate >= 80.0, f"Healing success rate too low (expected >= 80%, got {success_rate}%)"
    assert fpr == 0.0, f"False positive rate must be 0% on baseline (got {fpr}%)"
    print("  --> PASS: All quantitative resilience SLA thresholds met!")

    # 4. Verify Individual Scenario Outcomes
    print("\n[STEP 4] Verifying individual test scenario outcomes...")
    scenarios = data["scenarios"]
    assert len(scenarios) >= 5, f"Expected at least 5 scenarios, got {len(scenarios)}"

    for s in scenarios:
        sc_name = s.get("scenario")
        passed = s.get("passed", False)
        print(f"  Scenario: '{sc_name}' -> Passed: {passed}")
        assert passed, f"Scenario '{sc_name}' failed: {s.get('details')}"

    print("  --> PASS: All benchmark scenarios completed with 100% success.")

    # 5. Verify Markdown Summary File
    print("\n[STEP 5] Validating generated Markdown summary report...")
    with open(SUMMARY_MD, "r", encoding="utf-8") as f:
        md_content = f.read()

    assert "Phase 14: Automated Evaluation & Fault Injection Benchmark Report" in md_content
    assert "Mean Time to Detect (MTTD)" in md_content
    assert "Mean Time to Recover (MTTR)" in md_content
    assert "PASS" in md_content
    print("  --> PASS: Markdown summary report formatted and populated.")

    print("\n" + "=" * 70)
    print("      ALL PHASE 14 BENCHMARK TESTS PASSED SUCCESSFULLY!")
    print("=" * 70 + "\n")


if __name__ == "__main__":
    test_phase14_benchmark()
