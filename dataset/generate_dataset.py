#!/usr/bin/env python3
"""
Telemetry Dataset Generation Engine
===================================
MCA Dissertation: ML-Driven Self-Healing Microservice Gateway
Phase 7: Dataset Generation

This script orchestrates realistic client traffic and controlled chaos fault
injections across running microservices to generate an authentic telemetry dataset
for Isolation Forest anomaly detection training (Phase 8) and evaluation (Phase 17).

Outputs:
  - dataset/telemetry.csv         : Unsupervised feature dataset (used for ML training)
  - dataset/telemetry_labeled.csv : Ground-truth labeled dataset (used for model evaluation)
"""

import sys
import os
import time
import json
import random
import csv
import argparse
import urllib.request
import urllib.error
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

GATEWAY_URL = "http://localhost:8080"
SERVICES = {
    "order-service": {"port": 8081, "path": "/orders", "chaos_url": "http://localhost:8081/chaos"},
    "payment-service": {"port": 8082, "path": "/payments", "chaos_url": "http://localhost:8082/chaos"},
    "inventory-service": {"port": 8083, "path": "/inventory", "chaos_url": "http://localhost:8083/chaos"},
}

CSV_HEADER_UNLABELED = ["timestamp", "service", "cpu", "memory", "latency", "error_rate", "request_rate", "health_status"]
CSV_HEADER_LABELED = ["timestamp", "service", "cpu", "memory", "latency", "error_rate", "request_rate", "health_status", "scenario", "is_anomaly"]


def log(msg):
    """Print with flush=True to ensure real-time log streaming."""
    print(msg, flush=True)


def http_request(url, method="GET", data=None, timeout=10):
    """Utility function to execute HTTP requests using urllib."""
    req = urllib.request.Request(url, method=method)
    req.add_header("User-Agent", "TelemetryDatasetGenerator/1.0")
    if data is not None:
        req.add_header("Content-Type", "application/json")
        body = json.dumps(data).encode("utf-8")
    else:
        body = None
    try:
        with urllib.request.urlopen(req, data=body, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8") if e.fp else ""
    except Exception as e:
        return 0, str(e)


def check_all_services_up():
    """Verify that Gateway and all downstream microservices are running and healthy."""
    log("[INIT] Verifying services health...")
    endpoints = {
        "Gateway": f"{GATEWAY_URL}/actuator/health",
        "Order Service": "http://localhost:8081/actuator/health",
        "Payment Service": "http://localhost:8082/actuator/health",
        "Inventory Service": "http://localhost:8083/actuator/health",
    }
    all_ok = True
    for name, url in endpoints.items():
        status_code, resp_text = http_request(url, timeout=5)
        if status_code == 200:
            try:
                parsed = json.loads(resp_text)
                if parsed.get("status") == "UP":
                    log(f"  [+] {name:<18} is UP (HTTP 200)")
                    continue
            except json.JSONDecodeError:
                pass
        log(f"  [-] {name:<18} is NOT ready (HTTP {status_code})")
        all_ok = False
    return all_ok


def reset_all_chaos():
    """Ensure all microservices are in a clean, baseline state."""
    log("[CHAOS] Resetting faults across all services...")
    for svc_name, cfg in SERVICES.items():
        reset_url = f"{cfg['chaos_url']}/reset"
        status, _ = http_request(reset_url, method="POST", timeout=5)
        if status != 200:
            log(f"  [!] Warning: Failed to reset chaos on {svc_name} (HTTP {status})")


def send_concurrent_traffic(target_paths, num_requests=10, max_workers=5):
    """Send concurrent business requests through Gateway routes."""
    urls = [f"{GATEWAY_URL}{path}" for path in target_paths]
    
    def fetch(url):
        code, _ = http_request(url, timeout=12)
        return code

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [executor.submit(fetch, random.choice(urls)) for _ in range(num_requests)]
        return [f.result() for f in as_completed(futures)]


def collect_gateway_telemetry():
    """Trigger an immediate telemetry collection cycle on the Gateway and return latest snapshot."""
    collect_url = f"{GATEWAY_URL}/gateway/telemetry/collect"
    status, body = http_request(collect_url, method="POST", timeout=12)
    data = None
    if status == 200:
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            pass
    if data is None:
        latest_url = f"{GATEWAY_URL}/gateway/telemetry/latest"
        status, body = http_request(latest_url, method="GET", timeout=5)
        if status == 200:
            try:
                data = json.loads(body)
            except json.JSONDecodeError:
                pass
    if isinstance(data, list):
        return {item.get("serviceName", ""): item for item in data if isinstance(item, dict)}
    elif isinstance(data, dict):
        return data
    return {}


def append_records_to_csv(filepath, rows, header):
    """Append a batch of rows to a CSV file, adding header if file is new/empty."""
    file_exists = os.path.exists(filepath) and os.path.getsize(filepath) > 0
    with open(filepath, mode="a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        if not file_exists:
            writer.writerow(header)
        for r in rows:
            writer.writerow(r)


def format_row(snapshot, service_name, scenario="NORMAL", is_anomaly=0):
    """Format snapshot JSON into CSV rows."""
    svc_data = snapshot.get(service_name, {})
    if not svc_data:
        return None, None
    ts = svc_data.get("timestamp", datetime.now().isoformat())
    cpu = round(float(svc_data.get("cpu", 0.0)), 2)
    mem = round(float(svc_data.get("memory", 0.0)), 2)
    lat = round(float(svc_data.get("latency", 0.0)), 2)
    err = round(float(svc_data.get("errorRate", 0.0)), 2)
    req = round(float(svc_data.get("requestRate", 0.0)), 2)
    status = svc_data.get("healthStatus", "UP")

    unlabeled_row = [ts, service_name, f"{cpu:.2f}", f"{mem:.2f}", f"{lat:.2f}", f"{err:.2f}", f"{req:.2f}", status]
    labeled_row = [ts, service_name, f"{cpu:.2f}", f"{mem:.2f}", f"{lat:.2f}", f"{err:.2f}", f"{req:.2f}", status, scenario, is_anomaly]
    return unlabeled_row, labeled_row


def run_scenario(name, chaos_svc, chaos_endpoint, traffic_paths, duration_cycles=3, is_anomaly=1):
    """Execute a single scenario (normal or fault injection) and collect telemetry."""
    log(f"\n---> Scenario: [{name:<14}] (Target: {chaos_svc if chaos_svc else 'All':<17} Anomaly: {is_anomaly} Cycles: {duration_cycles})")
    
    # 1. Activate fault if specified
    if chaos_endpoint and chaos_svc:
        chaos_url = f"{SERVICES[chaos_svc]['chaos_url']}{chaos_endpoint}"
        status, _ = http_request(chaos_url, method="POST", timeout=5)
        log(f"     Activated chaos on {chaos_svc}: {chaos_endpoint} -> HTTP {status}")
        time.sleep(0.5)
    
    collected_unlabeled = []
    collected_labeled = []

    # 2. Generate traffic and collect telemetry across duration cycles
    for cycle in range(duration_cycles):
        # Fire concurrent requests to stimulate microservices
        send_concurrent_traffic(traffic_paths, num_requests=random.randint(6, 15), max_workers=4)
        
        # Trigger telemetry collection
        snapshot = collect_gateway_telemetry()
        if snapshot:
            for sname in SERVICES.keys():
                svc_is_anomaly = is_anomaly if (chaos_svc is None or sname == chaos_svc) else 0
                svc_scenario = name if (chaos_svc is None or sname == chaos_svc) else "NORMAL"
                
                u_row, l_row = format_row(snapshot, sname, scenario=svc_scenario, is_anomaly=svc_is_anomaly)
                if u_row:
                    collected_unlabeled.append(u_row)
                    collected_labeled.append(l_row)
        time.sleep(0.5)

    # 3. Clean up chaos fault if one was active
    if chaos_svc:
        reset_url = f"{SERVICES[chaos_svc]['chaos_url']}/reset"
        http_request(reset_url, method="POST", timeout=5)
        time.sleep(0.5)

    return collected_unlabeled, collected_labeled


def display_dataset_summary(csv_labeled_path):
    """Compute and print statistical analysis of the generated dataset."""
    log("\n" + "=" * 70)
    log("           DATASET GENERATION STATISTICAL SUMMARY")
    log("=" * 70)
    
    if not os.path.exists(csv_labeled_path):
        log("Dataset file not found.")
        return

    total = 0
    normal_count = 0
    anomaly_count = 0
    scenarios = {}
    services = {}
    
    cpu_vals = []
    mem_vals = []
    lat_vals = []
    err_vals = []
    req_vals = []

    with open(csv_labeled_path, mode="r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            total += 1
            is_ano = int(row.get("is_anomaly", 0))
            if is_ano == 1:
                anomaly_count += 1
            else:
                normal_count += 1
            
            sc = row.get("scenario", "UNKNOWN")
            scenarios[sc] = scenarios.get(sc, 0) + 1

            svc = row.get("service", "UNKNOWN")
            services[svc] = services.get(svc, 0) + 1

            try:
                cpu_vals.append(float(row["cpu"]))
                mem_vals.append(float(row["memory"]))
                lat_vals.append(float(row["latency"]))
                err_vals.append(float(row["error_rate"]))
                req_vals.append(float(row["request_rate"]))
            except (ValueError, KeyError):
                pass

    log(f"Total Observations Generated : {total}")
    log(f"Normal Samples               : {normal_count} ({normal_count/total*100:.1f}%)" if total else "")
    log(f"Anomalous Samples            : {anomaly_count} ({anomaly_count/total*100:.1f}%)" if total else "")
    
    log("\nDistribution by Scenario:")
    for sc, cnt in sorted(scenarios.items(), key=lambda x: x[1], reverse=True):
        log(f"  - {sc:<22}: {cnt:>5} records ({cnt/total*100:.1f}%)")

    log("\nDistribution by Service:")
    for svc, cnt in sorted(services.items(), key=lambda x: x[1], reverse=True):
        log(f"  - {svc:<22}: {cnt:>5} records ({cnt/total*100:.1f}%)")

    def stats(name, vals, unit=""):
        if not vals:
            return
        log(f"  {name:<15}: min={min(vals):.2f}{unit}, max={max(vals):.2f}{unit}, mean={sum(vals)/len(vals):.2f}{unit}")

    log("\nFeature Range Statistics:")
    stats("CPU Usage", cpu_vals, "%")
    stats("JVM Memory", mem_vals, "%")
    stats("Latency", lat_vals, "ms")
    stats("Error Rate", err_vals, "%")
    stats("Request Rate", req_vals, " req/s")
    log("=" * 70)


def main():
    parser = argparse.ArgumentParser(description="Telemetry Dataset Generator")
    parser.add_argument("--target-samples", type=int, default=1500, help="Target minimum number of telemetry records")
    parser.add_argument("--clean", action="store_true", help="Start fresh by recreating CSV output files")
    parser.add_argument("--csv-output", type=str, default="dataset/telemetry.csv", help="Path to unlabeled CSV")
    parser.add_argument("--labeled-output", type=str, default="dataset/telemetry_labeled.csv", help="Path to labeled CSV")
    args = parser.parse_args()

    log("=" * 70)
    log("   ML-Driven Self-Healing Microservice Gateway - Phase 7")
    log("              Telemetry Dataset Generator")
    log("=" * 70)
    log(f"Target Samples : {args.target_samples}")
    log(f"Clean Start    : {args.clean}")
    log(f"Unlabeled CSV  : {args.csv_output}")
    log(f"Labeled CSV    : {args.labeled_output}\n")

    # Step 1: Verify all microservices and Gateway are running
    if not check_all_services_up():
        log("\n[ERROR] Not all microservices are running. Please start services before generating dataset.")
        sys.exit(1)

    # Step 2: Clean slate reset
    reset_all_chaos()

    # Create dataset directory if needed
    os.makedirs(os.path.dirname(args.csv_output), exist_ok=True)

    if args.clean:
        if os.path.exists(args.csv_output):
            os.remove(args.csv_output)
        if os.path.exists(args.labeled_output):
            os.remove(args.labeled_output)
        log("[CLEAN] Existing dataset files cleared for fresh run.")

    # All business paths
    all_paths = ["/orders", "/payments", "/inventory"]

    total_records = 0
    round_num = 1

    # Check existing line count
    if os.path.exists(args.labeled_output):
        with open(args.labeled_output, mode="r", encoding="utf-8") as f:
            total_records = max(0, sum(1 for _ in f) - 1)
        log(f"[RESUME] Found existing {total_records} records in {args.labeled_output}.")

    start_time = time.time()

    while total_records < args.target_samples:
        log(f"\n======================================================================")
        log(f">>> Wave #{round_num:02d} | Progress: {total_records} / {args.target_samples} records ({total_records/args.target_samples*100:.1f}%)")
        log(f"======================================================================")

        # 1. Normal Baseline Traffic (5 cycles = 15 records)
        u, l = run_scenario("NORMAL", None, None, all_paths, duration_cycles=5, is_anomaly=0)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 2. Chaos: High Latency on Payment Service (2 cycles = 6 records, 2 anomaly, 4 normal)
        u, l = run_scenario("SLOW_LATENCY", "payment-service", "/slow?delayMs=1500", ["/payments"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # Normalization (3 cycles)
        u, l = run_scenario("NORMAL", None, None, all_paths, duration_cycles=3, is_anomaly=0)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 3. Chaos: Error 500 on Inventory Service (2 cycles)
        u, l = run_scenario("ERROR_500", "inventory-service", "/error?rate=100", ["/inventory"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # Normalization (3 cycles)
        u, l = run_scenario("NORMAL", None, None, all_paths, duration_cycles=3, is_anomaly=0)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 4. Chaos: CPU Stress on Order Service (2 cycles)
        u, l = run_scenario("CPU_STRESS", "order-service", "/cpu?durationSec=20", ["/orders"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # Normalization (3 cycles)
        u, l = run_scenario("NORMAL", None, None, all_paths, duration_cycles=3, is_anomaly=0)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 5. Chaos: Memory Pressure on Payment Service (2 cycles)
        u, l = run_scenario("MEMORY_STRESS", "payment-service", "/memory?megabytes=60", ["/payments"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 6. Chaos: Slow Latency on Order Service (2 cycles)
        u, l = run_scenario("SLOW_LATENCY", "order-service", "/slow?delayMs=2000", ["/orders"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 7. Chaos: Error 500 on Payment Service (2 cycles)
        u, l = run_scenario("ERROR_500", "payment-service", "/error?rate=100", ["/payments"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # 8. Chaos: CPU Stress on Inventory Service (2 cycles)
        u, l = run_scenario("CPU_STRESS", "inventory-service", "/cpu?durationSec=20", ["/inventory"], duration_cycles=2, is_anomaly=1)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        # Normalization (4 cycles)
        u, l = run_scenario("NORMAL", None, None, all_paths, duration_cycles=4, is_anomaly=0)
        append_records_to_csv(args.csv_output, u, CSV_HEADER_UNLABELED)
        append_records_to_csv(args.labeled_output, l, CSV_HEADER_LABELED)
        total_records += len(l)

        round_num += 1

    # Final reset
    reset_all_chaos()

    elapsed = time.time() - start_time
    log(f"\n[TIME] Generated {total_records} records in {elapsed:.1f} seconds ({total_records/elapsed:.1f} records/sec).")

    # Display statistical analysis
    display_dataset_summary(args.labeled_output)
    log("\n[SUCCESS] Phase 7 Dataset generation completed successfully.")


if __name__ == "__main__":
    main()
