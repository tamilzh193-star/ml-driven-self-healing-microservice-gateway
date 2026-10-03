# Microservice Telemetry Dataset

This directory contains the authentic, project-specific runtime telemetry datasets collected from the live Spring Boot microservice ecosystem (`order-service`, `payment-service`, `inventory-service`) via the Spring Cloud Gateway.

---

## 1. Datasets Overview

| File | Type | Target Usage | Records | Description |
| :--- | :--- | :--- | :--- | :--- |
| `telemetry.csv` | Unsupervised | **Phase 8 (Isolation Forest Training)** | 1,500+ | Unlabeled telemetry observations containing system metric features. |
| `telemetry_labeled.csv` | Supervised Ground Truth | **Phase 17 (Model Evaluation)** | 1,500+ | Annotated telemetry observations with scenario names and binary anomaly labels. |

---

## 2. Feature Schema

Both datasets record 8 standard runtime telemetry features:

| Column | Data Type | Unit / Range | Description | Source Endpoint |
| :--- | :--- | :--- | :--- | :--- |
| `timestamp` | String | ISO-8601 | Time of telemetry capture | Gateway System Clock |
| `service` | String | Name | Target microservice identifier | Target Service Config |
| `cpu` | Float | 0.0 – 100.0 % | Process CPU utilization | `/actuator/metrics/process.cpu.usage` |
| `memory` | Float | 0.0 – 100.0 % | JVM heap memory utilization | `/actuator/metrics/jvm.memory.used` / `max` |
| `latency` | Float | Milliseconds (ms) | HTTP response duration | Target endpoint & Actuator tag delta |
| `error_rate` | Float | 0.0 – 100.0 % | Ratio of 5xx HTTP responses | Actuator `/metrics/http.server.requests` |
| `request_rate` | Float | req/sec | Traffic throughput volume | Actuator interval request delta |
| `health_status` | String | `UP` / `DOWN` | Service operational health | `/actuator/health` |

### Evaluation Columns in `telemetry_labeled.csv`:
- `scenario`: Descriptive label of the workload condition (`NORMAL`, `SLOW_LATENCY`, `ERROR_500`, `CPU_STRESS`, `MEMORY_STRESS`).
- `is_anomaly`: Binary ground truth (`0` = normal healthy operation, `1` = degraded / anomalous operation).

---

## 3. Workload & Chaos Scenarios

The dataset was generated using `dataset/generate_dataset.py`, which systematically exercises the system under:

1. **Normal Baseline Traffic (~75–80% of data):**
   - Concurrent GET requests through Spring Cloud Gateway (`/orders`, `/payments`, `/inventory`).
   - Latencies consistently under 25 ms, 0% error rate, and normal CPU (< 15%).
2. **Artificial Response Delay (`SLOW_LATENCY`):**
   - Injected via `/chaos/slow?delayMs=1500` or `2000`.
   - Produces distinct latency spikes (> 1500 ms) while maintaining 0% error rates.
3. **HTTP 500 Error Surge (`ERROR_500`):**
   - Injected via `/chaos/error?rate=100`.
   - Forces endpoints to return HTTP 500 Internal Server Error, elevating `error_rate` to 100%.
4. **Process CPU Saturation (`CPU_STRESS`):**
   - Injected via `/chaos/cpu?durationSec=20`.
   - Spawns a high-computation thread raising process CPU utilization to ~70–90%.
5. **JVM Heap Memory Stress (`MEMORY_STRESS`):**
   - Injected via `/chaos/memory?megabytes=60`.
   - Bounded allocations elevating JVM memory percentage.
6. **Recovery Normalization:**
   - Evaluated by calling `/chaos/reset` after each fault to capture system recovery back to baseline.

---

## 4. How to Regenerate or Extend the Dataset

Ensure all 4 services are running:
- Gateway (:8080)
- Order Service (:8081)
- Payment Service (:8082)
- Inventory Service (:8083)

Run the generator script:
```powershell
# Generate fresh 1,500 sample dataset
python dataset/generate_dataset.py --target-samples 1500 --clean

# Extend existing dataset by appending additional samples
python dataset/generate_dataset.py --target-samples 2500
```
