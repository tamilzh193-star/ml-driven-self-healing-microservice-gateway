@echo off
title Self-Healing Microservice Cloud Gateway - Teardown
echo ==============================================================================
echo   STOPPING ALL SELF-HEALING CLOUD MICROSERVICES AND DAEMONS
echo ==============================================================================
echo.

set PORTS=5000 8080 8081 8082 8083

for %%p in (%PORTS%) do (
    echo [TEARDOWN] Searching for process listening on port %%p...
    for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":%%p " ^| findstr "LISTENING"') do (
        echo   - Terminating PID %%a on port %%p...
        taskkill /f /pid %%a >nul 2>&1
    )
)

echo.
echo ==============================================================================
echo   Verifying port release...
echo ==============================================================================
set ALL_STOPPED=1
for %%p in (%PORTS%) do (
    netstat -aon | findstr ":%%p " | findstr "LISTENING" >nul 2>&1
    if %errorlevel% equ 0 (
        echo [WARN] Port %%p is still occupied.
        set ALL_STOPPED=0
    ) else (
        echo [OK]   Port %%p is successfully freed.
    )
)

echo.
if %ALL_STOPPED% equ 1 (
    echo [SUCCESS] All 5 microservices, Gateway, and ML daemon stopped cleanly.
) else (
    echo [NOTICE] Some ports may take a few additional seconds to release.
)
echo ==============================================================================
pause
