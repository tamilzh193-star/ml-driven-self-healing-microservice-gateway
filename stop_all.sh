#!/usr/bin/env bash
# ==============================================================================
# Self-Healing Microservice Cloud Gateway - Unix/Linux/macOS Teardown
# ==============================================================================

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

echo "=============================================================================="
echo "  STOPPING ALL SELF-HEALING CLOUD MICROSERVICES AND DAEMONS"
echo "=============================================================================="

# Stop by PID file if exists
if [ -d "logs" ]; then
    for pidfile in logs/*.pid; do
        if [ -f "$pidfile" ]; then
            PID=$(cat "$pidfile")
            echo "Stopping PID $PID from $pidfile..."
            kill "$PID" 2>/dev/null || true
            rm -f "$pidfile"
        fi
    done
fi

# Fallback: stop by port
PORTS=(5000 8080 8081 8082 8083)
for port in "${PORTS[@]}"; do
    PID=$(lsof -ti :$port 2>/dev/null || true)
    if [ -n "$PID" ]; then
        echo "Terminating PID $PID listening on port $port..."
        kill -9 "$PID" 2>/dev/null || true
    fi
done

echo "[SUCCESS] All microservice ports freed."
