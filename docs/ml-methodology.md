# ML Methodology

> This document will be populated during Phase 8 (Isolation Forest Training).

## Algorithm: Isolation Forest

Isolation Forest is an unsupervised anomaly detection algorithm from scikit-learn.
It works by randomly selecting a feature and a split value, then isolating observations.
Anomalies are easier to isolate and thus have shorter average path lengths in the tree.

### Why Isolation Forest?

- **Unsupervised** — does not require manually labeled data
- **Efficient** — works well with high-dimensional data
- **Suitable for system telemetry** — effective at detecting unusual patterns in metrics

### Features Used

| Feature       | Description                          |
|---------------|--------------------------------------|
| cpu           | CPU usage percentage                 |
| memory        | Memory usage percentage              |
| latency       | Response latency in milliseconds     |
| error_rate    | Percentage of failed requests        |
| request_rate  | Number of requests per time window   |

### Training Process

*To be documented during Phase 8.*

### Model Evaluation

*To be documented during Phase 17.*
