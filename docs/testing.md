# Testing

> This document will be populated during Phase 16 (JMeter/Postman Testing).

## Testing Strategy

### Unit Tests
- Individual service endpoint validation

### Integration Tests
- Gateway routing verification
- ML service communication
- Healing engine workflow

### Load Testing (JMeter)
- Normal traffic simulation
- High-load stress testing
- Fault injection under load

### API Testing (Postman)
- Microservice endpoint testing
- ML service prediction API
- Chaos endpoint testing
- Dashboard API verification

## Test Collections

Test files will be stored under `tests/`:
- `tests/postman/` — Postman collection exports
- `tests/jmeter/` — JMeter test plans

## Evaluation Metrics

| Metric                    | Description                              |
|---------------------------|------------------------------------------|
| Anomaly Detection Time    | Time from fault to anomaly detection     |
| Recovery Time             | Time from detection to recovery          |
| MTTR                      | Mean Time To Recovery                    |
| Downtime                  | Total service unavailability duration    |
| False Positives           | Normal behavior flagged as anomaly       |
| Recovery Success Rate     | Successful recoveries / total attempts   |
