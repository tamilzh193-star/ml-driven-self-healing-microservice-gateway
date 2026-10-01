# Healing Mechanism

> This document will be populated during Phase 12 (Healing Engine).

## Overview

The healing engine receives anomaly detection results and selects an appropriate
recovery action based on the type and severity of the detected problem.

## Recovery Actions

### 1. Service Restart
- **When:** Service is unresponsive or in a degraded state
- **How:** Docker container restart via Docker API
- **Verification:** Actuator health check after configurable wait period

### 2. Traffic Redirection
- **When:** One instance is unhealthy but another is available
- **How:** Update gateway routing to redirect to healthy instance
- **Verification:** Confirm healthy instance is serving requests

### 3. Rate Limiting
- **When:** Service is overwhelmed by high request volume
- **How:** Apply request rate limits at the gateway level
- **Verification:** Monitor if error rate decreases

## Anomaly Confirmation

To avoid false positives triggering unnecessary recovery actions:
- Multiple consecutive anomalous observations are required
- A configurable cooldown period prevents repeated actions
- Parameters are configurable via application properties

## Recovery Verification

*To be documented during Phase 12.*
