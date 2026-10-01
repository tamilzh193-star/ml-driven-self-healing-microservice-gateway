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

