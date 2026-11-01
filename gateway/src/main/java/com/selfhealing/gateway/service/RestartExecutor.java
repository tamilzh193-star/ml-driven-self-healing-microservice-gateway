package com.selfhealing.gateway.service;

/**
 * Strategy interface for executing microservice restarts.
 * Implementations handle genuine container restarts (Docker) or local safe fallbacks (soft reset),
 * always reporting the truthful execution mode.
 */
public interface RestartExecutor {

    /**
     * Executes restart on the target service.
     *
     * @param serviceName target service logical name (e.g., "order-service")
     * @param baseUrl base URL of the service (e.g., "http://localhost:8081")
     * @return result indicating success, truthful mode, duration, and details
     */
    RestartResult restart(String serviceName, String baseUrl);

    /**
     * Checks if this executor is available in the current runtime environment.
     */
    boolean isAvailable();

    /**
     * Returns the execution mode identifier ("DOCKER" or "SOFT_RESET").
     */
    String getMode();
}
