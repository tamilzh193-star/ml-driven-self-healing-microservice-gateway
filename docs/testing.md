# Testing and Evaluation

This document details the testing architecture, empirical evaluation metrics, and fault injection benchmark results for the ML-Driven Self-Healing Microservice Gateway.

---

## 1. Testing Strategy

### Automated Fault Injection Benchmark (Phase 14)
- Automated evaluation harness located at [`benchmark/evaluate_self_healing.py`](file:///e:/self-healing-cloud%20project/benchmark/evaluate_self_healing.py).
- Programmatically exercises all microservices under controlled chaos injection:
  - Latency Degradation (`/chaos/slow`)
  - Internal Error Rate Spikes (`/chaos/error`)
  - Memory Saturation (`/chaos/memory`)
  - Transient Metric Spikes (Anti-Flapping Validation)
  - Immediate Retriggers (Anti-Thrashing Cooldown Validation)
  - Comparative Resilience (Availability with vs without self-healing)

### Integration & Regression Suites
- `tests/test_phase10_ml_integration.py` — Gateway ↔ ML Service live evaluation.
- `tests/test_phase11_anomaly_confirmation.py` — Sliding-window state machine verification.
- `tests/test_phase12_healing.py` — Closed-loop autonomous remediation and active recovery verification.
- `tests/test_phase13_database.py` — Non-blocking relational persistence and query verification.
- `tests/test_phase14_benchmark.py` — Quantitative resilience SLA benchmark verification.

---

## 2. Empirical Benchmark Evaluation Results (Phase 14)

The benchmark was executed against all active microservices, Spring Cloud Gateway, and the Isolation Forest ML service:

| Evaluation Metric | Empirical Value | Target SLA / Standard | Result |
| :--- | :--- | :--- | :--- |
| **Mean Time to Detect (MTTD)** | **8.89 s** | $< 25.0$ s (5s polling cycle) | **PASS** |
| **Mean Time to Recover (MTTR)** | **1.88 s** | $< 15.0$ s | **PASS** |
| **Healing Success Rate** | **100.0%** | $\ge 90.0\%$ | **PASS** |
| **Steady-State False Positive Rate (FPR)** | **0.0%** | $0.0\%$ (Zero spurious triggers) | **PASS** |
| **Anti-Flapping Filter Efficiency** | **100.0%** | $100.0\%$ (Transient spikes suppressed) | **PASS** |
| **Anti-Thrashing Cooldown Enforcement** | **100.0%** | $100.0\%$ (30s cooldown active) | **PASS** |
| **Availability Improvement** | **+100.0%** | Baseline: $0.0\%$ $\rightarrow$ Self-Healed: $100.0\%$ | **PASS** |

### Scenario Breakdown

| # | Scenario | Injected Chaos Fault | Autonomous Remediation | MTTD | MTTR | Outcome |
| :- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Steady-State Baseline | Healthy traffic (0 faults) | None (Engine idle) | - | - | **PASS** |
| 2 | Latency Degradation | Slow Response (1600ms) | `TRAFFIC_REDIRECTION` (Fallback mode) | 10.66 s | 0.10 s | **PASS** |
| 3 | Error Rate Surge | Active HTTP 500 fault | `RESTART` (Fault clearance) | 5.89 s | 0.14 s | **PASS** |
| 4 | Memory Saturation | JVM Heap Contention (50MB) | `RESTART` (State reset) | 10.13 s | 5.39 s | **PASS** |
| 5 | Anti-Flapping & Cooldown | Single spike + rapid retrigger | `SKIPPED_COOLDOWN` (Suppressed) | - | - | **PASS** |
| 6 | Comparative Resilience | Unhealed vs Self-Healed | Remediation + Active Health Probe | - | - | **PASS** |

---

## 3. Mathematical Metric Formulations

### Mean Time to Detect (MTTD)
$$\text{MTTD} = \frac{1}{N}\sum_{i=1}^{N}(t_{\text{confirmed}, i} - t_{\text{inject}, i})$$
Measures the latency between physical fault injection and the Phase 11 state transition into `CONFIRMED_ANOMALY`.

### Mean Time to Recover (MTTR)
$$\text{MTTR} = \frac{1}{N}\sum_{i=1}^{N}(t_{\text{recovered}, i} - t_{\text{confirmed}, i})$$
Measures the duration required for the remediation action (`TRAFFIC_REDIRECTION`, `RESTART`, `RATE_LIMIT`) to execute and verify downstream service health via Actuator probes.

### Healing Success Rate (HSR)
$$\text{HSR} = \frac{\text{Successful Recoveries}}{\text{Total Confirmed Anomalies}} \times 100\%$$
Quantifies the percentage of confirmed degradation events autonomously resolved without human operator intervention or exceeding retry limits.

### Availability Gain ($\Delta A$)
$$\Delta A = A_{\text{self-healed}} - A_{\text{unhealed}}$$
Quantifies the availability delta achieved by autonomous self-healing over an unhealed failure state.

---

## 4. Performance & Load Testing Suite (Phase 16)

Phase 16 introduces industrial-grade API integration testing and concurrent load performance benchmarking using **Postman Collection v2.1.0** and **Apache JMeter 5.x / Python multi-threaded runner**.

### 4.1 Postman API Collection & Environment
- **Collection Artifact**: [`tests/postman/self_healing_cloud_postman_collection.json`](file:///e:/self-healing-cloud%20project/tests/postman/self_healing_cloud_postman_collection.json)
- **Environment Configuration**: [`tests/postman/self_healing_cloud_environment.json`](file:///e:/self-healing-cloud%20project/tests/postman/self_healing_cloud_environment.json)
- **Test Organization (8 Modular Folders)**:
  1. `1. Microservice Business Routes`: Validates order creation, payment retrieval, and inventory state through Gateway routes.
  2. `2. Gateway Health & Telemetry`: Actuator health and scheduled 5-second telemetry snapshots.
  3. `3. Machine Learning Service`: Direct Flask Isolation Forest predictions and multidimensional feature inferences.
  4. `4. Anomaly Confirmation State Machine`: Real-time sliding window states, anti-flapping filters, and confirmed queue inspect.
  5. `5. Autonomous Healing Engine`: Remediation execution, cooldown state queries, and manual trigger controls.
  6. `6. Relational Persistence Layer`: Database table status, historical telemetry, and persistent audit logs.
  7. `7. Chaos Injection & Resilience Testing`: Injection of latency, error codes, and memory contention across services.
  8. `8. Operations Center Dashboard API`: Live JSON stats aggregator (`/api/dashboard/stats`) and UI action handlers.

### 4.2 Apache JMeter Load Test Plan (`self_healing_load_test.jmx`)
- **JMX Artifact**: [`tests/jmeter/self_healing_load_test.jmx`](file:///e:/self-healing-cloud%20project/tests/jmeter/self_healing_load_test.jmx)
- **Standalone Runner**: [`tests/jmeter/run_load_test.py`](file:///e:/self-healing-cloud%20project/tests/jmeter/run_load_test.py)
- **Benchmark Thread Groups**:
  - **Thread Group 1 (Baseline Traffic)**: 10 concurrent threads, 10 loops per thread (100 total requests) against `/orders`, `/inventory`, `/payments`.
  - **Thread Group 2 (Surge Stress Traffic)**: 20 concurrent threads, 15 loops per thread (300 total requests) generating mixed GET/POST payloads.
  - **Thread Group 3 (Operational & Telemetry Load)**: 5 concurrent threads executing high-frequency telemetry, anomaly state, and dashboard stats queries.

### 4.3 Empirical Performance Benchmark Results

| Metric | Baseline Load (TG1) | Surge Stress (TG2) | Telemetry Queries (TG3) |
| :--- | :--- | :--- | :--- |
| **Concurrency** | 10 workers | 20 workers | 5 workers |
| **Total Requests** | 100 | 300 | 50 |
| **Throughput (TPS)** | **195.0 req/s** | **147.41 req/s** | **59.88 req/s** |
| **Success Rate** | **100.0%** (100/100) | **100.0%** (300/300) | **100.0%** (50/50) |
| **Average Latency** | **24.31 ms** | **32.53 ms** | **69.79 ms** |
| **p50 (Median) Latency**| **15.94 ms** | **17.86 ms** | **6.46 ms** |
| **p90 Latency** | **49.23 ms** | **51.79 ms** | **360.90 ms** |
| **p95 Latency** | **89.68 ms** | **78.06 ms** | **413.94 ms** |
| **p99 Latency** | **162.76 ms** | **505.30 ms** | **483.56 ms** |
| **HTTP Status Breakdown**| 100x 200 OK | 220x 200 OK, 80x 201 Created | 50x 200 OK |

