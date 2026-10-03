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


