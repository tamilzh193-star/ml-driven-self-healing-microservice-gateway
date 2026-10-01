# ML-Driven Self-Healing Microservice Gateway

**Proactive Fault Detection and Autonomous Recovery in Java-Based Distributed Systems**

---

## Project Overview

This project implements an ML-driven self-healing microservice gateway that monitors
distributed Java Spring Boot microservices, detects anomalies using an Isolation Forest
model, and automatically performs recovery actions to maintain system health.

The system demonstrates a complete pipeline:

```
CLIENT → GATEWAY → MICROSERVICES → ACTUATOR → TELEMETRY → ML → ANOMALY → HEALING → RECOVERY → DATABASE → DASHBOARD
```

## Architecture

```
                         CLIENT
                           │
                    SPRING CLOUD GATEWAY (port 8080)
                     │         │         │
              ORDER SERVICE  PAYMENT   INVENTORY
              (port 8081)   SERVICE    SERVICE
                           (port 8082) (port 8083)
                     │         │         │
                  SPRING BOOT ACTUATOR (health/metrics)
                           │
                    TELEMETRY COLLECTION
                           │
                  PYTHON FLASK ML SERVICE (port 5000)
                    (Isolation Forest)
                           │
                     NORMAL / ANOMALY
                           │
                      HEALING ENGINE
                           │
            RESTART / REDIRECT / RATE LIMIT
                           │
                   RECOVERY VERIFICATION
                           │
                     MYSQL (port 3306)
                           │
                       DASHBOARD
```

## Technology Stack

| Layer           | Technology                                        |
|-----------------|---------------------------------------------------|
| Backend         | Java 17, Spring Boot, Spring Cloud Gateway, Maven |
| Microservices   | Order, Payment, Inventory (Spring Boot)           |
| Monitoring      | Spring Boot Actuator                              |
| ML              | Python 3.11, Flask, scikit-learn, Isolation Forest |
| Database        | MySQL                                             |
| Infrastructure  | Docker, Docker Compose                            |
| Dashboard       | Thymeleaf, HTML, CSS, JavaScript                  |
| Testing         | JMeter, Postman                                   |

## Prerequisites

- Java 17 (OpenJDK recommended)
- Apache Maven 3.9+
- Python 3.11
- Docker & Docker Compose
- Git
- MySQL 8.0 (via Docker)

## Project Structure

```
self-healing-cloud project/
│
├── gateway/                 # Spring Cloud Gateway (central entry point)
├── order-service/           # Order microservice
├── payment-service/         # Payment microservice
├── inventory-service/       # Inventory microservice
├── ml-service/              # Python Flask ML service (Isolation Forest)
├── dataset/                 # Generated telemetry datasets (CSV)
├── dashboard/               # Thymeleaf dashboard application
├── docker/                  # Dockerfiles for each service
├── docs/                    # Project documentation
├── tests/                   # JMeter/Postman test collections
├── docker-compose.yml       # Docker Compose configuration
├── .gitignore
└── README.md
```

## Ports

| Service           | Port |
|-------------------|------|
| Spring Cloud Gateway | 8080 |
| Order Service     | 8081 |
| Payment Service   | 8082 |
| Inventory Service | 8083 |
| ML Flask Service  | 5000 |
| MySQL             | 3306 |

## Quick Start

> **Note:** Detailed setup instructions will be added as each component is built.

### 1. Clone the Repository

```bash
git clone <repository-url>
cd "self-healing-cloud project"
```

### 2. Build All Java Services

```bash
# Build each service individually
cd order-service && mvn clean package && cd ..
cd payment-service && mvn clean package && cd ..
cd inventory-service && mvn clean package && cd ..
cd gateway && mvn clean package && cd ..
```

### 3. Start with Docker Compose

```bash
docker-compose up --build
```

### 4. Train the ML Model

```bash
cd ml-service
pip install -r requirements.txt
python train_model.py
python app.py
```

## Documentation

- [Architecture](docs/architecture.md)
- [ML Methodology](docs/ml-methodology.md)
- [Telemetry](docs/telemetry.md)
- [Healing Mechanism](docs/healing-mechanism.md)
- [API Reference](docs/api.md)
- [Testing](docs/testing.md)

## Demonstration Scenario

The system supports a full end-to-end demonstration:

1. Start all services → Dashboard shows all HEALTHY
2. Generate normal traffic → Collect baseline telemetry
3. Inject fault (e.g., `/chaos/slow` on Payment Service)
4. Telemetry shows degradation (latency ↑, error rate ↑)
5. ML service detects ANOMALY via Isolation Forest
6. Healing engine selects recovery action
7. System performs recovery (restart / redirect / rate limit)
8. Actuator verifies service health
9. Service recovers → Dashboard shows recovery event

## Development Phases

- [x] Phase 1: Project Foundation
- [ ] Phase 2: Microservices (Order, Payment, Inventory)
- [ ] Phase 3: Spring Cloud Gateway
- [ ] Phase 4: Spring Boot Actuator
- [ ] Phase 5: Telemetry Collection
- [ ] Phase 6: Fault Injection
- [ ] Phase 7: Dataset Generation
- [ ] Phase 8: Isolation Forest Training
- [ ] Phase 9: Flask ML API
- [ ] Phase 10: Gateway ↔ ML Integration
- [ ] Phase 11: Anomaly Confirmation Logic
- [ ] Phase 12: Healing Engine
- [ ] Phase 13: Docker Compose
- [ ] Phase 14: MySQL
- [ ] Phase 15: Dashboard
- [ ] Phase 16: JMeter/Postman Testing
- [ ] Phase 17: Evaluation
- [ ] Phase 18: End-to-End Demonstration

## License

This project is developed as an MCA dissertation project.

---

*ML-Driven Self-Healing Microservice Gateway — Proactive Fault Detection and Autonomous Recovery*
