#!/usr/bin/env bash
# ==============================================================================
# Self-Healing Microservice Cloud Gateway - Unix/Linux/macOS Launcher
# ==============================================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

echo "=============================================================================="
echo "  ML-DRIVEN SELF-HEALING MICROSERVICE CLUSTER - ORCHESTRATION LAUNCHER"
echo "=============================================================================="

# 1. Prerequisite Checks
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 is required."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "[ERROR] java (JDK 17+) is required."; exit 1; }

# 2. Check JAR existence
if [ ! -f "gateway/target/gateway-1.0.0.jar" ]; then
    echo "[BUILD] Compiling and packaging services with Maven..."
    mvn clean package -DskipTests
fi

mkdir -p logs

echo "[1/5] Launching Machine Learning Anomaly Detection Service (Port 5000)..."
python3 ml-service/app.py > logs/ml-service.log 2>&1 &
echo $! > logs/ml-service.pid

echo "[2/5] Launching Order Business Service (Port 8081)..."
java -jar order-service/target/order-service-1.0.0.jar > logs/order-service.log 2>&1 &
echo $! > logs/order-service.pid

echo "[3/5] Launching Payment Business Service (Port 8082)..."
java -jar payment-service/target/payment-service-1.0.0.jar > logs/payment-service.log 2>&1 &
echo $! > logs/payment-service.pid

echo "[4/5] Launching Inventory Business Service (Port 8083)..."
java -jar inventory-service/target/inventory-service-1.0.0.jar > logs/inventory-service.log 2>&1 &
echo $! > logs/inventory-service.pid

echo "Waiting 6 seconds for microservices to initialize..."
sleep 6

echo "[5/5] Launching Spring Cloud API Gateway & Dashboard (Port 8080)..."
(cd gateway && java -jar target/gateway-1.0.0.jar > ../logs/gateway.log 2>&1 & echo $! > ../logs/gateway.pid)

echo "Waiting for cluster services to report healthy..."
python3 scripts/verify_cluster.py --wait --timeout-sec 60

echo ""
echo "[READY] Operations Center Dashboard: http://localhost:8080/dashboard"
echo "To stop cluster: ./stop_all.sh"
