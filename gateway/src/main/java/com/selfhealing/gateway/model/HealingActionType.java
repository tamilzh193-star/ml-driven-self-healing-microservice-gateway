package com.selfhealing.gateway.model;

/**
 * Enumeration of supported autonomous remediation strategies.
 */
public enum HealingActionType {
    /**
     * Service restart / reboot / crash recovery.
     * Triggered by critical failures such as high error rates (5xx), memory leaks, or DOWN health.
     */
    RESTART,

    /**
     * Traffic redirection / fallback routing.
     * Triggered by severe latency degradation to shed load and route callers to safe fallback.
     */
    TRAFFIC_REDIRECTION,

    /**
     * Dynamic rate limiting / traffic shaping.
     * Triggered by request burst surges or CPU overload to protect downstream services from collapse.
     */
    RATE_LIMIT
}
