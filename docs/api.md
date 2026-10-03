# API Reference

> This document will be populated as services are built.

## Microservice Endpoints

### Order Service (port 8081)

| Method | Endpoint        | Description          |
|--------|-----------------|----------------------|
| GET    | /orders         | List all orders      |
| POST   | /orders         | Create a new order   |
| GET    | /orders/{id}    | Get order by ID      |

### Payment Service (port 8082)

| Method | Endpoint        | Description            |
|--------|-----------------|------------------------|
| POST   | /payments       | Process a payment      |
| GET    | /payments/{id}  | Get payment status     |

### Inventory Service (port 8083)

| Method | Endpoint            | Description          |
|--------|---------------------|----------------------|
| GET    | /inventory          | List all inventory   |
| GET    | /inventory/{id}     | Get item by ID       |
| POST   | /inventory/update   | Update inventory     |

## Gateway Routes (port 8080)

| Method | Endpoint         | Routed Target Service                  |
|--------|------------------|----------------------------------------|
| ANY    | /orders/**       | http://localhost:8081/orders/**        |
| ANY    | /payments/**     | http://localhost:8082/payments/**      |
| ANY    | /inventory/**    | http://localhost:8083/inventory/**     |
| GET    | /gateway/health  | Gateway self-status check              |


## ML Service (port 5000)

The Machine Learning Service is implemented using Python Flask and serves the trained Isolation Forest anomaly detection model.

| Method | Endpoint | Description |
|:---|:---|:---|
| `GET` | `/health` | Service health status and model load verification |
| `GET` | `/model/info` | Trained model metadata, hyperparameters, and evaluation metrics |
| `POST` | `/predict` | Ingests telemetry metrics and outputs anomaly prediction (single or batch) |

### 1. `GET /health`
- **Response (HTTP 200):**
```json
{
  "status": "UP",
  "service": "ml-service",
  "model_loaded": true,
  "scaler_loaded": true,
  "timestamp": "2026-10-03T14:33:14.281188"
}
```

### 2. `GET /model/info`
- **Response (HTTP 200):**
```json
{
  "algorithm": "IsolationForest",
  "framework": "scikit-learn",
  "features": ["cpu", "memory", "latency", "error_rate", "request_rate"],
  "parameters": {
    "n_estimators": 100,
    "contamination": 0.14,
    "random_state": 42
  },
  "evaluation_metrics": {
    "accuracy": 0.9622,
    "precision": 0.8843,
    "recall": 0.8527,
    "f1_score": 0.8682
  }
}
```

### 3. `POST /predict`
Evaluates telemetry metrics using the fitted `StandardScaler` and `IsolationForest`.

- **Request Body (Single Service):**
```json
{
  "service": "payment-service",
  "cpu": 1.80,
  "memory": 1.30,
  "latency": 1520.40,
  "error_rate": 0.0,
  "request_rate": 1.10
}
```

- **Response (HTTP 200 - Anomaly):**
```json
{
  "service": "payment-service",
  "prediction": "ANOMALY",
  "is_anomaly": true,
  "anomaly_score": -0.0461,
  "timestamp": "2026-10-03T14:35:01.123456",
  "features": {
    "cpu": 1.80,
    "memory": 1.30,
    "latency": 1520.40,
    "error_rate": 0.0,
    "request_rate": 1.10
  }
}
```

- **Request Body (Batch Array):**
```json
[
  {"service": "order-service", "cpu": 1.5, "memory": 1.1, "latency": 2.0, "error_rate": 0.0, "request_rate": 1.5},
  {"service": "payment-service", "cpu": 1.2, "memory": 1.3, "latency": 1515.0, "error_rate": 0.0, "request_rate": 1.0}
]
```

- **Response (HTTP 200 - Array):**
```json
[
  {"service": "order-service", "prediction": "NORMAL", "is_anomaly": false, "anomaly_score": 0.12},
  {"service": "payment-service", "prediction": "ANOMALY", "is_anomaly": true, "anomaly_score": -0.05}
]
```


## Chaos / Fault Injection Endpoints

Chaos endpoints are provided on each microservice (and routed through the Gateway) for controlled, reversible fault injection.

| Method | Endpoint | Query Parameters | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/chaos/slow` | `delayMs` (default: 1500) | Injects artificial latency into business request handling |
| `POST` | `/chaos/error` | `active` (default: true) | Simulates service errors by forcing endpoints to return HTTP 500 |
| `POST` | `/chaos/cpu` | `durationSec` (default: 30) | Generates controlled mathematical workload to raise process CPU to ~70-85% |
| `POST` | `/chaos/memory` | `megabytes` (default: 50) | Allocates bounded heap memory buffers to raise JVM heap usage |
| `POST` | `/chaos/reset` | none | Instantly clears all injected delays, errors, CPU threads, and releases memory |
| `GET` | `/chaos/status` | none | Returns current status of all chaos injection toggles |

### Service URLs:
- **Order Service:** `http://localhost:8081/chaos/**` (or Gateway: `http://localhost:8080/order-service/chaos/**`)
- **Payment Service:** `http://localhost:8082/chaos/**` (or Gateway: `http://localhost:8080/payment-service/chaos/**`)
- **Inventory Service:** `http://localhost:8083/chaos/**` (or Gateway: `http://localhost:8080/inventory-service/chaos/**`)


## Anomaly Confirmation Endpoints (port 8080)

The Gateway evaluates ML predictions using a sliding-window hysteresis state machine (`HEALTHY` -> `SUSPECT` -> `CONFIRMED_ANOMALY` -> `RECOVERING`).
Only `CONFIRMED_ANOMALY` states trigger autonomous self-healing in Phase 12.

| Method | Endpoint | Description |
|:---|:---|:---|
| `GET` | `/gateway/anomaly/status` | Returns anomaly confirmation state machine status for all microservices |
| `GET` | `/gateway/anomaly/status/{serviceName}` | Returns sliding-window confirmation state for a specific service |
| `GET` | `/gateway/anomaly/confirmed` | Returns all microservices currently confirmed anomalous (escalation input to Healing Engine) |
| `POST` | `/gateway/anomaly/reset` | Resets confirmation sliding windows and state machines for all services |
| `POST` | `/gateway/anomaly/reset/{serviceName}` | Resets confirmation state machine for a specific service |

### Example Response: `GET /gateway/anomaly/status/payment-service`
```json
{
  "serviceName": "payment-service",
  "status": "CONFIRMED_ANOMALY",
  "consecutiveAnomalies": 3,
  "consecutiveNormal": 0,
  "anomaliesInWindow": 3,
  "windowSize": 5,
  "averageAnomalyScore": -0.0695,
  "firstAnomalyTimestamp": "2026-10-03T17:51:47.123",
  "confirmedTimestamp": "2026-10-03T17:51:57.307",
  "lastEvaluationTimestamp": "2026-10-03T17:51:57.307",
  "latestPrediction": {
    "service": "payment-service",
    "prediction": "ANOMALY",
    "is_anomaly": true,
    "anomaly_score": -0.0695
  }
}
```

## Autonomous Healing Endpoints (port 8080)

The Autonomous Healing Engine consumes confirmed anomalies from Phase 11, selects targeted remediation strategies (`RESTART`, `TRAFFIC_REDIRECTION`, `RATE_LIMIT`), enforces anti-thrashing cooldowns, verifies post-healing recovery, and maintains an immutable audit trail.

| Method | Endpoint | Description |
|:---|:---|:---|
| `GET` | `/gateway/healing/status` | Current status of active traffic redirections, rate limits, cooldowns, and retry counts |
| `GET` | `/gateway/healing/history` | Audit log of all autonomous remediation actions and recovery verifications |
| `POST` | `/gateway/healing/trigger/{serviceName}` | Manually triggers closed-loop self-healing on a specific service (with `force` flag) |
| `POST` | `/gateway/healing/reset` | Resets all active remediation states, cooldowns, retry counts, and audit logs |

### Example Response: `GET /gateway/healing/status`
```json
{
  "activeRedirections": [],
  "activeRateLimits": {},
  "activeCooldowns": {
    "payment-service": "2026-10-03T18:55:00.000Z"
  },
  "retryCounts": {
    "payment-service": 0
  },
  "totalAuditRecords": 1
}
```

### Example Audit Record: `GET /gateway/healing/history`
```json
[
  {
    "id": "c1f7b0a2-8921-4f93-b271-97b744d0149f",
    "serviceName": "payment-service",
    "actionType": "TRAFFIC_REDIRECTION",
    "triggerReason": "Severe latency degradation (1607.8 ms) exceeding SLA; activating traffic redirection and fallback mode",
    "anomalyScore": -0.0695,
    "attemptCount": 1,
    "timestamp": "2026-10-03T18:53:20.123Z",
    "actionStatus": "RECOVERED",
    "recoveryStatus": "RECOVERED",
    "recoveryDetails": "Service 'payment-service' verified healthy: /actuator/health is UP and probe responded within SLA",
    "cooldownUntil": "2026-10-03T18:53:50.123Z",
    "executionDurationMs": 15
  }
]
```



