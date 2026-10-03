# System Architecture

> This document will be populated as the system is built.

## Overview

The ML-Driven Self-Healing Microservice Gateway uses the following architecture:

```
CLIENT
  │
  ▼
SPRING CLOUD GATEWAY (port 8080)
  │
  ├──► ORDER SERVICE (port 8081)
  ├──► PAYMENT SERVICE (port 8082)
  └──► INVENTORY SERVICE (port 8083)
         │
         ▼
  SPRING BOOT ACTUATOR (health + metrics)
         │
         ▼
  TELEMETRY COLLECTION
         │
         ▼
  PYTHON FLASK ML SERVICE (port 5000)
  (Isolation Forest anomaly detection)
         │
         ▼
  HEALING ENGINE
  (Restart / Traffic Redirect / Rate Limit)
         │
         ▼
  RECOVERY VERIFICATION
         │
         ▼
  MYSQL DATABASE (port 3306)
         │
         ▼
  THYMELEAF DASHBOARD
```

## Component Details

### 1. Spring Cloud Gateway (`port 8080`)
- Built on Spring Boot 3.2.5, Spring Cloud 2023.0.1, and reactive Netty.
- Routes:
  - `/orders/**` ➔ `http://localhost:8081` (Order Service)
  - `/payments/**` ➔ `http://localhost:8082` (Payment Service)
  - `/inventory/**` ➔ `http://localhost:8083` (Inventory Service)
- Health check: `/gateway/health`
- Actuator endpoints: `/actuator/health`, `/actuator/metrics`

### 2. Spring Boot Actuator
- Integrated into all 4 services (`order-service`, `payment-service`, `inventory-service`, `api-gateway`).
- Exposes:
  - `/actuator/health` (with full component details for recovery verification)
  - `/actuator/metrics` (CPU, memory, request volume, response latency)
- Serves as the raw metric source polled by Phase 5 telemetry collection.
- Distinct from ML anomaly detection (Actuator provides raw metrics; Isolation Forest detects anomalies).

### 3. Telemetry Collection Engine
- Hosted within `api-gateway` (`TelemetryCollectorService`).
- Scheduled polling every 5000 ms using reactive `WebClient`.
- Active latency probing on business paths (`/orders`, `/payments`, `/inventory`) combined with Actuator interval delta calculations to prevent internal polling metric dilution.
- Appends telemetry records to `dataset/telemetry.csv` and exposes `/gateway/telemetry/latest`, `/history`, and `/collect`.

### 4. Machine Learning Service (`port 5000`)
- Built on Python 3.11, Flask, and scikit-learn.
- Uses **Isolation Forest** ($n_{\text{estimators}} = 100, \text{contamination} = 0.14$) with `StandardScaler`.
- Trained on authentic microservice telemetry dataset (`dataset/telemetry.csv`).
- Exposes:
  - `GET /health`: Service health and model/scaler verification.
  - `GET /model/info`: Model metadata, hyperparameters, and evaluation metrics.
  - `POST /predict`: Real-time anomaly inference (supports single and batch evaluation).

### 5. Gateway ↔ ML Integration (`Phase 10`)
- Spring Cloud Gateway communicates with the ML Service via `MlClientService`.
- At the conclusion of each 5-second polling cycle, the collected telemetry batch is dispatched to `POST http://localhost:5000/predict`.
- Anomaly predictions (`NORMAL` vs `ANOMALY`) and continuous anomaly scores are logged in real time and cached in an in-memory thread-safe buffer.
- Gateway REST endpoints:
  - `GET /gateway/ml/status`: Live ML service connectivity check and ping latency.
  - `GET /gateway/ml/latest`: Latest anomaly classifications across all registered services.
  - `GET /gateway/ml/history`: Rolling window of recent anomaly predictions.
  - `POST /gateway/ml/evaluate`: On-demand telemetry collection and ML inference trigger.

### 6. Anomaly Confirmation & Anti-Flapping Engine (`Phase 11`)
- **Anti-Flapping Requirement:** In autonomous cloud environments, single isolated metric spikes (e.g. Stop-The-World JVM GC pauses, network packet retransmissions, brief burst traffic) must never trigger premature recovery or service restarts.
- **Sliding-Window Hysteresis:** Anomaly status progresses through four discrete states:
  - `HEALTHY`: Baseline normal operation.
  - `SUSPECT`: 1 or 2 anomalous cycles detected ($< K$). Transient noise is suppressed; zero recovery actions are dispatched.
  - `CONFIRMED_ANOMALY`: Sustained degradation confirmed ($\ge 3$ consecutive cycles OR $\ge 3/5$ in sliding window). Escalated to Phase 12 Autonomous Healing Engine.
  - `RECOVERING`: Fault cleared; service requires consecutive clean cycles before restoring to `HEALTHY`.
- Endpoints:
  - `GET /gateway/anomaly/status`: Confirmation state machine details and rolling window inspection.
  - `GET /gateway/anomaly/confirmed`: Microservices currently confirmed anomalous for autonomous recovery dispatch.
  - `POST /gateway/anomaly/reset`: Administrative reset of state machines and history windows.

### 7. Autonomous Healing Engine (`Phase 12`)
- **Closed-Loop Healing Flow:**
  ```
  CONFIRMED_ANOMALY (from Phase 11)
          ↓
  HealingEngineService
          ↓
  RemediationStrategySelector (Rule-based Decision)
          ├── High error rate / memory saturation / DOWN -> RESTART
          ├── High latency exceeding SLA (> 800ms) -> TRAFFIC_REDIRECTION
          └── Traffic surge / CPU contention -> RATE_LIMIT
          ↓
  RemediationExecutionService & SelfHealingRemediationFilter
          ↓
  Post-Remediation Recovery Verification (Actuator health + business latency probe)
          ↓
  State Transition (RECOVERED -> reset to HEALTHY, or FAILED_RECOVERY)
          ↓
  Immutable Healing Audit Trail (Logged for Phase 13 MySQL persistence)
  ```
- **Anti-Thrashing Cooldown:** Remediation actions are followed by a configurable cooldown (default: 30 seconds) suppressing redundant actions (`SKIPPED_COOLDOWN`).
- **Maximum Retry Protection:** Prevents endless recovery loops by capping attempts (default: 3 retries); services failing beyond this limit transition to `MAX_RETRIES_EXCEEDED` / `CRITICAL`.
- **Runtime Remediation Enforcers:**
  - `RESTART`: Issues recovery reset to clear faults and reboot service state.
  - `TRAFFIC_REDIRECTION`: `SelfHealingRemediationFilter` intercepts requests to the degraded route and returns graceful fallback responses (`FALLBACK_REDIRECTION`) until verified healthy.
  - `RATE_LIMIT`: Throttles burst traffic, shedding load with HTTP 429 (`TOO_MANY_REQUESTS`) to protect downstream microservices from cascading failure.
- Endpoints:
  - `GET /gateway/healing/status`: Active redirections, rate limits, cooldowns, and retry counters.
  - `GET /gateway/healing/history`: Immutable audit trail of all autonomous recovery executions.
  - `POST /gateway/healing/trigger/{serviceName}`: Administrative on-demand healing trigger.
  - `POST /gateway/healing/reset`: Administrative reset of engine states, cooldowns, and audit logs.

### 8. Relational Persistence Layer (`Phase 13`)
- **Dual-Mode Persistence Architecture:**
  - Designed for enterprise persistence in MySQL (production default port `3306`) with zero-configuration embedded H2 (`MODE=MySQL`) fallback.
  - Eliminates external database boot barriers while ensuring production schema compatibility (`telemetry_records`, `anomaly_predictions`, `healing_audit_logs`).
  - Configured with resilient HikariCP connection pooling (`initialization-fail-timeout: -1`), guaranteeing the Gateway boots up even if the relational database is temporarily offline.
- **Reactive Safety & Non-Blocking Netty Event Loops:**
  - Spring Cloud Gateway runs on reactive Project Reactor Netty. Synchronous JDBC blocking calls on Netty reactor threads lead to event loop starvation and gateway lockups.
  - `DatabasePersistenceService` schedules all synchronous JDBC inserts and reads onto `Schedulers.boundedElastic()`, ensuring reactive request routing is never hindered.
- **Schema & Tables:**
  - `telemetry_records`: Records timestamp, service, CPU, memory, latency, error rate, request rate, and health status with composite indexes for time-series range queries.
  - `anomaly_predictions`: Stores ML inference results, raw anomaly scores, and classification (`NORMAL` vs `ANOMALY`).
  - `healing_audit_logs`: Immutable audit trail of closed-loop remediations (`RESTART`, `TRAFFIC_REDIRECTION`, `RATE_LIMIT`), anomaly scores, attempt counts, execution durations, recovery verification status, and cooldown timestamps.
- **Persistence Endpoints:**
  - `GET /gateway/db/status`: Database connection state, driver name, product version, and table row counts.
  - `GET /gateway/db/telemetry`: Historical telemetry metrics with service filtering and limit pagination.
  - `GET /gateway/db/healing`: Historical remediation audit records.
  - `GET /gateway/db/anomalies`: Historical ML anomaly predictions.
  - `POST /gateway/db/reset`: Administrative reset/truncation of database tables.
