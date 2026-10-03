package com.selfhealing.gateway.model;

/**
 * Status of a healing remediation action and its post-healing recovery lifecycle.
 */
public enum HealingStatus {
    /** Remediation action identified and scheduled */
    INITIATED,

    /** Remediation currently executing */
    EXECUTING,

    /** Remediation completed and post-healing health/telemetry verification SUCCEEDED */
    RECOVERED,

    /** Post-healing health/telemetry verification FAILED */
    FAILED_RECOVERY,

    /** Action suppressed because service is currently within anti-thrashing cooldown */
    SKIPPED_COOLDOWN,

    /** Action aborted because service reached maximum retry limit without recovery */
    MAX_RETRIES_EXCEEDED
}
