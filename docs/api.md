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

| Method | Endpoint   | Description              |
|--------|------------|--------------------------|
| POST   | /predict   | Predict anomaly status   |

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

