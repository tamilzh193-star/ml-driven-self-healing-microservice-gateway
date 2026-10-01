# Telemetry Collection

> This document will be populated during Phase 5 (Telemetry Collection).

## Overview

Telemetry data is collected from each microservice to monitor system health
and feed the ML anomaly detection model.

## Metrics Collected

| Metric        | Source                    | Description                      |
|---------------|---------------------------|----------------------------------|
| timestamp     | System clock              | When the metric was recorded     |
| service_name  | Application config        | Which service reported           |
| cpu           | JVM Runtime / OS MXBean   | CPU usage percentage             |
| memory        | JVM Runtime               | Memory usage percentage          |
| latency       | Request interceptor       | Average response time (ms)       |
| error_rate    | Error counter             | Percentage of failed requests    |
| request_rate  | Request counter           | Requests per collection window   |
| health_status | Spring Boot Actuator      | UP / DOWN / DEGRADED             |

## Collection Mechanism

*To be documented during Phase 5.*

## Storage Format

CSV files stored under `dataset/` for ML training.
MySQL `service_metrics` table for runtime storage.
