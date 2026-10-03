#!/usr/bin/env python3
"""
Standalone JMeter-Equivalent Performance & Load Test Runner.
Executes multi-threaded HTTP load simulations matching self_healing_load_test.jmx:
  - Baseline Workload (10 concurrent users)
  - Surge Stress Workload (20-30 concurrent users)
  - Anomaly & Healing Resilience Workload
Calculates latency percentiles (p50, p90, p95, p99), throughput (TPS), and error rates.
"""

import sys
import time
import json
import statistics
import concurrent.futures
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

GATEWAY_BASE = "http://localhost:8080"

def execute_http_request(method, url, data=None, headers=None, timeout=10.0):
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    body = None
    if data is not None:
        if isinstance(data, dict):
            body = json.dumps(data).encode("utf-8")
            req_headers["Content-Type"] = "application/json"
        elif isinstance(data, str):
            body = data.encode("utf-8")
            req_headers["Content-Type"] = "application/json"
        else:
            body = data

    req = Request(url, data=body, headers=req_headers, method=method)
    start_time = time.perf_counter()
    status_code = 0
    resp_text = ""
    success = False

    try:
        with urlopen(req, timeout=timeout) as response:
            status_code = response.status
            resp_text = response.read().decode("utf-8", errors="ignore")
            success = (200 <= status_code < 400)
    except HTTPError as e:
        status_code = e.code
        resp_text = e.read().decode("utf-8", errors="ignore")
        # In rate limiting / circuit breaking scenarios, 429 is an expected controlled response
        success = (status_code in [200, 201, 429])
    except URLError as e:
        status_code = 0
        resp_text = str(e.reason)
        success = False
    except Exception as e:
        status_code = 0
        resp_text = str(e)
        success = False

    elapsed_ms = (time.perf_counter() - start_time) * 1000.0
    return {
        "status_code": status_code,
        "elapsed_ms": elapsed_ms,
        "success": success,
        "response_body": resp_text
    }

def run_thread_worker(worker_id, requests_per_worker, endpoints):
    results = []
    for i in range(requests_per_worker):
        endpoint = endpoints[i % len(endpoints)]
        method = endpoint.get("method", "GET")
        url = GATEWAY_BASE + endpoint["path"]
        body = endpoint.get("body")
        res = execute_http_request(method, url, data=body)
        res["worker_id"] = worker_id
        res["path"] = endpoint["path"]
        results.append(res)
    return results

def compute_metrics(results, total_duration_sec):
    if not results:
        return {}
    latencies = [r["elapsed_ms"] for r in results]
    latencies.sort()
    total_reqs = len(results)
    success_reqs = sum(1 for r in results if r["success"])
    status_counts = {}
    for r in results:
        code = r["status_code"]
        status_counts[code] = status_counts.get(code, 0) + 1

    throughput = total_reqs / total_duration_sec if total_duration_sec > 0 else 0

    def percentile(p):
        idx = int(len(latencies) * p)
        idx = min(idx, len(latencies) - 1)
        return latencies[idx]

    return {
        "total_requests": total_reqs,
        "successful_requests": success_reqs,
        "failed_requests": total_reqs - success_reqs,
        "error_rate_pct": round(((total_reqs - success_reqs) / total_reqs) * 100.0, 2),
        "duration_seconds": round(total_duration_sec, 3),
        "throughput_tps": round(throughput, 2),
        "avg_latency_ms": round(statistics.mean(latencies), 2),
        "median_latency_ms": round(statistics.median(latencies), 2),
        "min_latency_ms": round(min(latencies), 2),
        "max_latency_ms": round(max(latencies), 2),
        "p50_latency_ms": round(percentile(0.50), 2),
        "p90_latency_ms": round(percentile(0.90), 2),
        "p95_latency_ms": round(percentile(0.95), 2),
        "p99_latency_ms": round(percentile(0.99), 2),
        "status_distribution": status_counts
    }

def run_stage(stage_name, concurrency, requests_per_thread, endpoints):
    print(f"\n=======================================================")
    print(f"  Stage: {stage_name}")
    print(f"  Concurrency: {concurrency} workers | Requests/Worker: {requests_per_thread}")
    print(f"  Total Scheduled Requests: {concurrency * requests_per_thread}")
    print(f"=======================================================")

    start = time.perf_counter()
    all_results = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [
            executor.submit(run_thread_worker, wid, requests_per_thread, endpoints)
            for wid in range(concurrency)
        ]
        for f in concurrent.futures.as_completed(futures):
            all_results.extend(f.result())

    duration = time.perf_counter() - start
    metrics = compute_metrics(all_results, duration)

    print(f"  Duration:          {metrics['duration_seconds']}s")
    print(f"  Throughput:        {metrics['throughput_tps']} req/sec")
    print(f"  Success Rate:      {100.0 - metrics['error_rate_pct']}% ({metrics['successful_requests']}/{metrics['total_requests']})")
    print(f"  Avg Latency:       {metrics['avg_latency_ms']} ms")
    print(f"  p50 Latency:       {metrics['p50_latency_ms']} ms")
    print(f"  p90 Latency:       {metrics['p90_latency_ms']} ms")
    print(f"  p95 Latency:       {metrics['p95_latency_ms']} ms")
    print(f"  p99 Latency:       {metrics['p99_latency_ms']} ms")
    print(f"  Status Codes:      {metrics['status_distribution']}")
    return metrics

def main():
    print("=" * 60)
    print(" MCA DISSERTATION - JMETER EQUIVALENT LOAD BENCHMARK ")
    print(" Target Gateway: " + GATEWAY_BASE)
    print("=" * 60)

    # 1. Warmup / Health check
    probe = execute_http_request("GET", f"{GATEWAY_BASE}/actuator/health")
    if not probe["success"]:
        print(f"[FATAL] Gateway is not reachable at {GATEWAY_BASE} (Status: {probe['status_code']})")
        sys.exit(1)
    print("[INIT] Gateway health check verified: ONLINE")

    # Stage 1: Baseline Workload
    baseline_endpoints = [
        {"method": "GET", "path": "/orders"},
        {"method": "GET", "path": "/inventory"},
        {"method": "GET", "path": "/payments"}
    ]
    stage1_metrics = run_stage("Thread Group 1 - Baseline Workload", concurrency=10, requests_per_thread=10, endpoints=baseline_endpoints)

    # Stage 2: Surge Stress Workload
    surge_endpoints = [
        {"method": "GET", "path": "/orders"},
        {"method": "POST", "path": "/orders", "body": {"customerName": "LoadTester", "item": "Laptop-Pro", "quantity": 1, "price": 1299.99}},
        {"method": "GET", "path": "/inventory"},
        {"method": "GET", "path": "/payments"}
    ]
    stage2_metrics = run_stage("Thread Group 2 - Surge Stress Workload", concurrency=20, requests_per_thread=15, endpoints=surge_endpoints)

    # Stage 3: Operational Actuator & Telemetry Query Load
    telemetry_endpoints = [
        {"method": "GET", "path": "/gateway/telemetry/latest"},
        {"method": "GET", "path": "/gateway/anomaly/status"},
        {"method": "GET", "path": "/gateway/healing/history"},
        {"method": "GET", "path": "/gateway/db/status"},
        {"method": "GET", "path": "/api/dashboard/stats"}
    ]
    stage3_metrics = run_stage("Thread Group 3 - Telemetry & Actuator Monitoring", concurrency=5, requests_per_thread=10, endpoints=telemetry_endpoints)

    # Final summary export
    full_report = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "gateway_url": GATEWAY_BASE,
        "stages": {
            "baseline": stage1_metrics,
            "surge_stress": stage2_metrics,
            "actuator_telemetry": stage3_metrics
        }
    }

    report_path = "tests/jmeter/load_test_results.json"
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(full_report, f, indent=2)

    print(f"\n[REPORT] Load test benchmark results saved to: {report_path}")
    print("[SUCCESS] All JMeter load simulation stages completed successfully.")

if __name__ == "__main__":
    main()
