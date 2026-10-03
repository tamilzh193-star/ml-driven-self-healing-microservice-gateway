# Telemetry Collection & Dataset Generation

## Overview

The **Telemetry Collection Engine** is hosted within the Spring Cloud Gateway (`api-gateway`). It acts as a continuous monitoring agent that periodically polls the Spring Boot Actuator endpoints of all downstream microservices (`order-service`, `payment-service`, and `inventory-service`).

The collected telemetry serves two key purposes:
1. **Offline Training:** Saved to `dataset/telemetry.csv` to generate the training dataset for the Isolation Forest machine learning model (Phase 7 & Phase 8).
2. **Online Anomaly Detection:** Fed into the Python Flask ML Service in real time for autonomous fault detection and healing (Phase 10).

---

## Metrics Specification

| Metric | Actuator Source Endpoint | Data Type | Formula / Unit | Description |
| :--- | :--- | :--- | :--- | :--- |
| `timestamp` | System Clock | String (ISO-8601) | `YYYY-MM-DDTHH:mm:ss` | Time of observation |
| `service` | Configuration mapping | String | `order-service`, `payment-service`, `inventory-service` | Monitored microservice |
| `cpu` | `/actuator/metrics/process.cpu.usage` | Double | `value * 100` (%) | JVM process CPU utilization |
| `memory` | `/actuator/metrics/jvm.memory.used`, `/jvm.memory.max` | Double | `(used / max) * 100` (%) | JVM heap memory utilization |
| `latency` | `/actuator/metrics/http.server.requests` & Probe | Double | `(TOTAL_TIME / COUNT) * 1000` (ms) | Mean HTTP request response time |
| `error_rate` | `/actuator/metrics/http.server.requests` (status 5xx) | Double | `(errors / count) * 100` (%) | Percentage of failed HTTP requests |
| `request_rate` | `/actuator/metrics/http.server.requests` | Double | `delta(COUNT) / delta(seconds)` (req/sec) | Incoming request throughput |
| `health_status`| `/actuator/health` | String | `UP` / `DOWN` / `UNKNOWN` | Actuator overall health status |

---

## Dataset Schema & Storage

All observations are stored under `dataset/`:

### 1. `dataset/telemetry.csv` (Unsupervised ML Training)
```csv
timestamp,service,cpu,memory,latency,error_rate,request_rate,health_status
2026-10-03T11:15:25.423,order-service,2.21,0.97,10.57,0.00,2.83,UP
```

### 2. `dataset/telemetry_labeled.csv` (Supervised Model Evaluation)
```csv
timestamp,service,cpu,memory,latency,error_rate,request_rate,health_status,scenario,is_anomaly
2026-10-03T11:15:49.369,payment-service,0.88,1.23,1509.86,0.00,1.58,UP,SLOW_LATENCY,1
```

---

## Dataset Generation Engine (`dataset/generate_dataset.py`)

The dataset generation script exercises the microservices through automated traffic waves:
- **Normal Workload:** Concurrent HTTP GET requests across `/orders`, `/payments`, and `/inventory`.
- **Fault Scenarios:**
  - `SLOW_LATENCY`: POST `/chaos/slow?delayMs=1500` or `2000`
  - `ERROR_500`: POST `/chaos/error?rate=100`
  - `CPU_STRESS`: POST `/chaos/cpu?durationSec=20`
  - `MEMORY_STRESS`: POST `/chaos/memory?megabytes=60`
  - `NORMAL`: Periodic resets via POST `/chaos/reset` to capture recovery behavior.

### Execution Command:
```powershell
python dataset/generate_dataset.py --target-samples 1500 --clean
```

---

## Gateway Telemetry Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/gateway/telemetry/latest` | Returns the latest snapshot for all registered microservices |
| `GET` | `/gateway/telemetry/history` | Returns the rolling buffer (last 100 telemetry snapshots) |
| `POST` | `/gateway/telemetry/collect` | Triggers an immediate collection cycle and returns results |
