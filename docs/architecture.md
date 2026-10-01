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

*To be added in subsequent phases.*
