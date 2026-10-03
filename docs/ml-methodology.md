# Machine Learning Methodology

## 1. Overview & Problem Formulation

In a distributed microservice architecture, failure conditions manifest across interdependent runtime dimensions: response times degrade, JVM heaps accumulate garbage, threads consume excessive CPU, or request pipelines fail with HTTP 500 errors. 

Traditional threshold-based alerting (e.g., alert if CPU > 80%) suffers from high false-positive rates during benign traffic spikes and fails to capture multi-metric degradations (e.g., slight latency increase combined with elevated error rate).

To achieve proactive self-healing, this project formulates anomaly detection as an **unsupervised multivariate anomaly detection** task over live runtime telemetry streams, powered by **Isolation Forest**.

---

## 2. Algorithm: Isolation Forest

### Theoretical Foundation
Proposed by Liu, Ting, and Zhou (2008), the **Isolation Forest** algorithm operates on an explicit principle: **anomalies are "few and different"**, making them susceptible to isolation much earlier in recursive random partitioning than normal instances.

An ensemble of $t$ Isolation Trees ($iTrees$) is constructed:
1. Given a sub-sampled dataset $X = \{x_1, \dots, x_n\}$ of $d$ dimensions.
2. An $iTree$ recursively partitions $X$ by randomly selecting an attribute $q$ and a split value $p \in [\min(q), \max(q)]$.
3. The recursive division terminates when either:
   - The tree reaches a maximum height limit $h_{\max} = \lceil\log_2(n)\rceil$.
   - $|X| \le 1$.
   - All data points in $X$ have identical feature values.

### Path Length and Anomaly Score
Let $h(x)$ denote the path length of observation $x$, defined as the number of edges traversed from the root of an $iTree$ to the terminating external node.

The average path length $\mathbb{E}(h(x))$ across an ensemble of $t$ trees is normalized against the average path length of an unsuccessful search in a Binary Search Tree:

$$c(n) = 2 \ln(n - 1) + 0.5772156649 - \frac{2(n - 1)}{n}$$

The anomaly score $s(x, n)$ for instance $x$ is defined as:

$$s(x, n) = 2^{-\frac{\mathbb{E}(h(x))}{c(n)}}$$

**Interpretation:**
- $\mathbb{E}(h(x)) \to 0 \implies s \to 1$: Observation is isolated with very few splits $\rightarrow$ **Highly anomalous**.
- $\mathbb{E}(h(x)) \to n - 1 \implies s \to 0$: Observation requires extensive partitioning $\rightarrow$ **Normal inlier**.
- $\mathbb{E}(h(x)) \to c(n) \implies s \to 0.5$: Observation does not exhibit distinct anomaly characteristics.

### Why Isolation Forest for Self-Healing Gateways?
1. **Unsupervised:** Microservices experience novel, unforeseen failure modes in production where labeled anomaly data is unavailable or prohibitively expensive to curate.
2. **Sub-linear Complexity:** Training complexity is $O(t \cdot \psi \log \psi)$ and inference complexity is $O(t \cdot \log \psi)$, where $\psi$ is the sub-sampling size. This enables real-time sub-millisecond inference suitable for gateway monitoring.
3. **Multivariate Coupling:** Detects compound anomalies where no individual metric violates a univariate threshold, but the combination of metrics represents an anomalous system state.

---

## 3. Telemetry Feature Space

The model ingests 5 continuous operational metrics extracted from Spring Boot Actuator endpoints:

$$\mathbf{X} = [\text{cpu},\, \text{memory},\, \text{latency},\, \text{error\_rate},\, \text{request\_rate}]$$

| Feature | Unit | Actuator Source | Rationale in Fault Detection |
| :--- | :--- | :--- | :--- |
| `cpu` | % (0–100) | `/actuator/metrics/process.cpu.usage` | Captures CPU saturation, thread starvation, infinite loops, and heavy computational bottlenecks. |
| `memory` | % (0–100) | `/actuator/metrics/jvm.memory.used` / `max` | Identifies memory leaks, JVM heap exhaustion, GC thrashing, and bounded buffer pressure. |
| `latency` | ms | Target endpoint probe & `/http.server.requests` | Pinpoints network degradation, database connection exhaustion, downstream slow calls, and artificial delays. |
| `error_rate` | % (0–100) | Tagged HTTP 5xx `/http.server.requests` | Detects service crashes, unhandled exceptions, downstream 502/503/504 errors, and HTTP 500 error surges. |
| `request_rate` | req/s | Interval $\Delta\text{COUNT} / \Delta\text{seconds}$ | Differentiates organic traffic spikes or DDoS attempts from internal service failures. |

---

## 4. Preprocessing & Normalization

Because telemetry metrics operate across disparate dynamic ranges:
- Latency spans $[0.5\text{ ms},\, 2500\text{ ms}]$
- Error rate and CPU span $[0\%,\, 100\%]$
- JVM Memory spans $[1\%,\, 30\%]$

Features are normalized using **`StandardScaler`**:

$$z = \frac{x - \mu}{\sigma}$$

This maps all features onto comparable zero-mean, unit-variance distributions, ensuring distance splits in feature space treat latency spikes, memory bloat, and error rate surges with proportional sensitivity.

---

## 5. Model Architecture & Hyperparameters

| Hyperparameter | Value | Rationale |
| :--- | :--- | :--- |
| `n_estimators` | `100` | Standard ensemble size providing stability and smooth anomaly score distributions without excessive memory footprint. |
| `contamination` | `0.14` | Calibrated to the empirical anomaly proportion observed in the telemetry dataset (~14.6%). Defines the decision boundary threshold. |
| `max_samples` | `auto` | Automatically sets sub-sample size to $\min(256, n)$, mitigating swamping and masking effects. |
| `random_state` | `42` | Guarantees deterministic reproducibility across training runs. |

---

## 6. Training Pipeline (`ml-service/train_model.py`)

The training pipeline executes in three stages:
1. **Unsupervised Fitting:** Ingests `dataset/telemetry.csv`, fits `StandardScaler`, and trains `IsolationForest`.
2. **Ground-Truth Evaluation:** Validates fitted model against `dataset/telemetry_labeled.csv` to calculate Precision, Recall, F1-Score, and Confusion Matrix.
3. **Artifact Serialization:** Serializes the trained estimator (`isolation_forest.joblib`), preprocessor (`scaler.joblib`), and training metadata (`model_metadata.json`) into `ml-service/model/`.

---

## 7. Experimental Evaluation Results (Phase 8)

The model was validated against the ground-truth labeled evaluation dataset (`dataset/telemetry_labeled.csv`, $N = 1,536$ observations):

### Overall Classification Performance

| Metric | Score | Formula / Definition | Academic Significance |
| :--- | :--- | :--- | :--- |
| **Accuracy** | **96.22%** | $(TP + TN) / (P + N)$ | Overall correctness across normal and fault conditions |
| **Precision** | **88.43%** | $TP / (TP + FP)$ | High confidence when flagging true faults (low false alarms) |
| **Recall** | **85.27%** | $TP / (TP + FN)$ | High sensitivity in detecting genuine microservice degradation |
| **F1-Score** | **86.82%** | $2 \cdot \frac{\text{Precision} \cdot \text{Recall}}{\text{Precision} + \text{Recall}}$ | Harmonic mean demonstrating balanced anomaly discrimination |

### Confusion Matrix
- **True Normal (TN):** `1,287` (98.1% of healthy states correctly classified)
- **False Alarm (FP):** `25` (1.9% false positive rate)
- **Missed Fault (FN):** `33`
- **True Anomaly (TP):** `191` (85.3% true fault detection rate)

### Per-Scenario Detection Breakdown

| Scenario | Injected Condition | Total Records | Detected Anomalies | Detection Rate |
| :--- | :--- | :--- | :--- | :--- |
| `NORMAL` | Baseline healthy traffic | 1,312 | 25 | **98.1% Normal** (1.9% FP) |
| `SLOW_LATENCY` | 1500–2000 ms artificial delay | 64 | 64 | **100.0% Detected** |
| `ERROR_500` | HTTP 500 Internal Error rate | 64 | 64 | **100.0% Detected** |
| `MEMORY_STRESS` | JVM heap allocation pressure | 32 | 32 | **100.0% Detected** |
| `CPU_STRESS` | High-computation CPU thread | 64 | 31 | **48.4% Detected** |

---

## 8. Serving Architecture for Phase 9 & 10

In **Phase 9**, the serialized artifacts are loaded by a **Python Flask REST API** (`http://localhost:5000`):
- `POST /predict`: Ingests real-time telemetry JSON, transforms features with `scaler.joblib`, predicts anomaly state (`NORMAL` vs. `ANOMALY`), and computes continuous anomaly confidence scores.
- `GET /health`: Model status and feature schema health check.
- `GET /model/info`: Returns model metadata, training timestamp, and evaluation metrics.

