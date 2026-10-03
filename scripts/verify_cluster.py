#!/usr/bin/env python3
"""
Self-Healing Cloud Gateway - Cluster Health Verification Utility
Pings all 5 microservice tiers and prints status summary table.
Usage:
  python scripts/verify_cluster.py [--wait] [--timeout-sec 60]
"""

import sys
import time
import json
import argparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

SERVICES = [
    {
        "name": "Machine Learning Service",
        "key": "ml-service",
        "port": 5000,
        "url": "http://localhost:5000/health",
        "expected_field": "status",
        "expected_value": "HEALTHY"
    },
    {
        "name": "Order Business Service",
        "key": "order-service",
        "port": 8081,
        "url": "http://localhost:8081/actuator/health",
        "expected_field": "status",
        "expected_value": "UP"
    },
    {
        "name": "Payment Business Service",
        "key": "payment-service",
        "port": 8082,
        "url": "http://localhost:8082/actuator/health",
        "expected_field": "status",
        "expected_value": "UP"
    },
    {
        "name": "Inventory Business Service",
        "key": "inventory-service",
        "port": 8083,
        "url": "http://localhost:8083/actuator/health",
        "expected_field": "status",
        "expected_value": "UP"
    },
    {
        "name": "API Gateway & Operations Center",
        "key": "api-gateway",
        "port": 8080,
        "url": "http://localhost:8080/actuator/health",
        "expected_field": "status",
        "expected_value": "UP"
    }
]

def check_single_service(svc, timeout=3.0):
    start = time.perf_counter()
    try:
        req = Request(svc["url"], headers={"Accept": "application/json"})
        with urlopen(req, timeout=timeout) as resp:
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            data = json.loads(resp.read().decode())
            status_val = data.get(svc["expected_field"])
            is_up = (resp.status == 200 and status_val == svc["expected_value"])
            return {
                "name": svc["name"],
                "port": svc["port"],
                "url": svc["url"],
                "status_code": resp.status,
                "latency_ms": round(elapsed_ms, 1),
                "is_up": is_up,
                "detail": str(status_val)
            }
    except Exception as e:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return {
            "name": svc["name"],
            "port": svc["port"],
            "url": svc["url"],
            "status_code": 0,
            "latency_ms": round(elapsed_ms, 1),
            "is_up": False,
            "detail": f"OFFLINE ({e.__class__.__name__})"
        }

def check_all_services():
    results = []
    for svc in SERVICES:
        results.append(check_single_service(svc))
    return results

def print_health_table(results):
    print("=" * 82)
    print(" SELF-HEALING CLOUD MICROSERVICE CLUSTER - HEALTH STATUS ")
    print("=" * 82)
    print(f"{'Service Name':<34} | {'Port':<5} | {'Latency':<9} | {'Health':<10} | {'Status'}")
    print("-" * 82)

    all_healthy = True
    for r in results:
        status_label = "[ONLINE] " if r["is_up"] else "[OFFLINE]"
        if not r["is_up"]:
            all_healthy = False
        print(f"{r['name']:<34} | {r['port']:<5} | {r['latency_ms']:>6.1f} ms | {r['detail']:<10} | {status_label}")

    print("=" * 82)
    if all_healthy:
        print("[SUCCESS] All 5 cluster components are UP, healthy, and operational!")
    else:
        offline_count = sum(1 for r in results if not r["is_up"])
        print(f"[ALERT] {offline_count} of {len(results)} service(s) are currently OFFLINE.")
    print("=" * 82)
    return all_healthy

def main():
    parser = argparse.ArgumentParser(description="Verify cluster health for Self-Healing Gateway")
    parser.add_argument("--wait", action="store_true", help="Wait and poll until all services are UP")
    parser.add_argument("--timeout-sec", type=int, default=60, help="Max wait duration in seconds (default: 60)")
    args = parser.parse_args()

    if not args.wait:
        results = check_all_services()
        is_healthy = print_health_table(results)
        sys.exit(0 if is_healthy else 1)

    print(f"[INIT] Waiting for all 5 cluster services to reach healthy state (Timeout: {args.timeout_sec}s)...")
    start_time = time.time()
    attempt = 1

    while time.time() - start_time < args.timeout_sec:
        results = check_all_services()
        if all(r["is_up"] for r in results):
            print(f"\n[READY] Cluster fully initialized in {round(time.time() - start_time, 1)} seconds (Attempt #{attempt}):\n")
            print_health_table(results)
            sys.exit(0)

        elapsed = round(time.time() - start_time, 1)
        up_count = sum(1 for r in results if r["is_up"])
        print(f"  Attempt #{attempt} ({elapsed}s elapsed): {up_count}/{len(SERVICES)} services UP... waiting", end="\r", flush=True)
        time.sleep(2.5)
        attempt += 1

    print("\n[TIMEOUT] Reached timeout before all services came online.")
    results = check_all_services()
    print_health_table(results)
    sys.exit(1)

if __name__ == "__main__":
    main()
