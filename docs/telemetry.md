# Telemetry Collection

## Overview

The **Telemetry Collection Engine** is hosted within the Spring Cloud Gateway (`api-gateway`). It acts as a continuous monitoring agent that periodically polls the Spring Boot Actuator endpoints of all downstream microservices (`order-service`, `payment-service`, and `inventory-service`).

The collected telemetry serves two key purposes:
1. **Offline Training:** Appended to `dataset/telemetry.csv` to generate the training dataset for the Isolation Forest machine learning model (Phase 7).
2. **Online Anomaly Detection:** Fed into the Python Flask ML Service in real time for autonomous fault detection and healing (Phase 10).

---

## Metrics Specification

| Metric | Actuator Source Endpoint | Data Type | Formula / Unit | Description |
| :--- | :--- | :--- | :--- | :--- |
| `timestamp` | System Clock | String (ISO-8601) | `YYYY-MM-DDTHH:mm:ss` | Time of observation |
| `service` | Configuration mapping | String | `order-service`, `payment-service`, `inventory-service` | Monitored microservice |
| `cpu` | `/actuator/metrics/process.cpu.usage` | Double | `value * 100` (%) | JVM process CPU utilization |
| `memory` | `/actuator/metrics/jvm.memory.used`, `/jvm.memory.max` | Double | `(used / max) * 100` (%) | JVM heap memory utilization |
| `latency` | `/actuator/metrics/http.server.requests` | Double | `(TOTAL_TIME / COUNT) * 1000` (ms) | Mean HTTP request response time |
| `error_rate` | `/actuator/metrics/http.server.requests` (status 5xx) | Double | `(errors / count) * 100` (%) | Percentage of failed HTTP requests |
| `request_rate` | `/actuator/metrics/http.server.requests` | Double | `delta(COUNT) / delta(seconds)` (req/sec) | Incoming request throughput |
| `health_status`| `/actuator/health` | String | `UP` / `DOWN` / `UNKNOWN` | Actuator overall health status |

---

## CSV Format

All observations are stored in `dataset/telemetry.csv` with the header:
```csv
timestamp,service,cpu,memory,latency,error_rate,request_rate,health_status
```

Sample record:
```csv
2026-10-01T21:30:00.123,payment-service,14.50,38.20,12.40,0.00,5.20,UP
```

---

## Resilience & Fault-Tolerance

- **Timeout Protection:** Actuator queries use a strict 2-second timeout via `WebClient`.
- **Safe Fallback:** If a service is down or unreachable (e.g., container restart or crash), the collector records `health_status=DOWN`, `cpu=0.0`, `latency=0.0`, and does **not** fail or interrupt gateway traffic.

---

## Gateway Telemetry Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/gateway/telemetry/latest` | Returns the latest snapshot for all registered microservices |
| `GET` | `/gateway/telemetry/history` | Returns the rolling buffer (last 100 telemetry snapshots) |
| `POST` | `/gateway/telemetry/collect` | Triggers an immediate collection cycle and returns results |
