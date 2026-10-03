package com.selfhealing.gateway.model;

/**
 * State machine statuses for microservice anomaly confirmation.
 */
public enum AnomalyStatus {
    /**
     * Normal operational behavior. No anomalies in recent window.
     */
    HEALTHY,

    /**
     * Warning state: 1 or 2 anomalies observed, but under confirmation threshold.
     * Prevents flapping from transient network jitters or GC pauses.
     */
    SUSPECT,

    /**
     * Sustained anomaly confirmed across multiple consecutive/window cycles.
     * Escalates to the Autonomous Healing Engine.
     */
    CONFIRMED_ANOMALY,

    /**
     * Service was in CONFIRMED_ANOMALY and is now normalizing back to baseline.
     */
    RECOVERING
}
