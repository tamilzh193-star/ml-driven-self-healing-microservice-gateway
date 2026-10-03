@echo off
title Self-Healing Microservice Cloud Gateway - Launcher
echo ==============================================================================
echo   ML-DRIVEN SELF-HEALING MICROSERVICE CLUSTER - ORCHESTRATION LAUNCHER
echo ==============================================================================
echo.

:: 1. Environment Prerequisite Checks
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python 3 is not found in PATH. Please install Python 3.11+.
    pause
    exit /b 1
)

java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java 17 is not found in PATH. Please install JDK 17+.
    pause
    exit /b 1
)

:: 2. Check Fat JAR Existence
if not exist "gateway\target\gateway-1.0.0.jar" (
    echo [BUILD] JAR artifacts not found. Compiling and packaging services with Maven...
    call mvn clean package -DskipTests
    if %errorlevel% neq 0 (
        echo [ERROR] Maven build failed.
        pause
        exit /b 1
    )
)

echo [1/5] Launching Machine Learning Anomaly Detection Service (Port 5000)...
start "ML Anomaly Detection Service [5000]" cmd /k "cd /d %~dp0 && python ml-service\app.py"

echo [2/5] Launching Order Business Service (Port 8081)...
start "Order Business Service [8081]" cmd /k "cd /d %~dp0 && java -jar order-service\target\order-service-1.0.0.jar"

echo [3/5] Launching Payment Business Service (Port 8082)...
start "Payment Business Service [8082]" cmd /k "cd /d %~dp0 && java -jar payment-service\target\payment-service-1.0.0.jar"

echo [4/5] Launching Inventory Business Service (Port 8083)...
start "Inventory Business Service [8083]" cmd /k "cd /d %~dp0 && java -jar inventory-service\target\inventory-service-1.0.0.jar"

echo.
echo Waiting 6 seconds for microservices to initialize before Gateway startup...
timeout /t 6 /nobreak >nul

echo [5/5] Launching Spring Cloud API Gateway & Dashboard (Port 8080)...
start "API Gateway & Operations Center [8080]" cmd /k "cd /d %~dp0 && java -jar gateway\target\gateway-1.0.0.jar"

echo.
echo ==============================================================================
echo   Waiting for all 5 cluster services to reach HEALTHY operational state...
echo ==============================================================================
python scripts\verify_cluster.py --wait --timeout-sec 60

if %errorlevel% equ 0 (
    echo.
    echo ==============================================================================
    echo   CLUSTER READY: Launching Operations Center Dashboard in Web Browser...
    echo ==============================================================================
    start "" "http://localhost:8080/dashboard"
    echo.
    echo   - Operations Center UI:   http://localhost:8080/dashboard
    echo   - Gateway Health:         http://localhost:8080/actuator/health
    echo   - To stop all services:   Run stop_all.bat
    echo.
) else (
    echo.
    echo [WARNING] One or more services took longer than expected to report healthy.
    echo Check the individual terminal windows for logs.
)

pause
