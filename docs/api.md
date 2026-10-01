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

*To be documented during Phase 3.*

## ML Service (port 5000)

| Method | Endpoint   | Description              |
|--------|------------|--------------------------|
| POST   | /predict   | Predict anomaly status   |

## Chaos Endpoints

| Method | Endpoint      | Description                    |
|--------|---------------|--------------------------------|
| POST   | /chaos/slow   | Inject artificial delay        |
| POST   | /chaos/error  | Increase error rate            |
| POST   | /chaos/cpu    | Simulate CPU stress            |
| POST   | /chaos/memory | Simulate memory pressure       |
| POST   | /chaos/reset  | Reset all injected faults      |
