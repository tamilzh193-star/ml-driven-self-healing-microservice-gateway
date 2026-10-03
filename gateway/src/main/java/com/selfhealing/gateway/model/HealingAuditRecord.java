package com.selfhealing.gateway.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record capturing autonomous self-healing remediation actions and verification results.
 * Structured with relational-friendly primitive fields for seamless transition to MySQL JPA in Phase 13.
 */
public class HealingAuditRecord {

    private String id;
    private String serviceName;
    private HealingActionType actionType;
    private String triggerReason;
    private Double anomalyScore;
    private int attemptCount;
    private String timestamp;
    private HealingStatus actionStatus;
    private String recoveryStatus;
    private String recoveryDetails;
    private String cooldownUntil;
    private Long executionDurationMs;

    public HealingAuditRecord() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toString();
    }

    public HealingAuditRecord(String serviceName, HealingActionType actionType, String triggerReason,
                              Double anomalyScore, int attemptCount) {
        this.id = UUID.randomUUID().toString();
        this.serviceName = serviceName;
        this.actionType = actionType;
        this.triggerReason = triggerReason;
        this.anomalyScore = anomalyScore;
        this.attemptCount = attemptCount;
        this.timestamp = Instant.now().toString();
        this.actionStatus = HealingStatus.INITIATED;
        this.recoveryStatus = "PENDING";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public HealingActionType getActionType() {
        return actionType;
    }

    public void setActionType(HealingActionType actionType) {
        this.actionType = actionType;
    }

    public String getTriggerReason() {
        return triggerReason;
    }

    public void setTriggerReason(String triggerReason) {
        this.triggerReason = triggerReason;
    }

    public Double getAnomalyScore() {
        return anomalyScore;
    }

    public void setAnomalyScore(Double anomalyScore) {
        this.anomalyScore = anomalyScore;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public HealingStatus getActionStatus() {
        return actionStatus;
    }

    public void setActionStatus(HealingStatus actionStatus) {
        this.actionStatus = actionStatus;
    }

    public String getRecoveryStatus() {
        return recoveryStatus;
    }

    public void setRecoveryStatus(String recoveryStatus) {
        this.recoveryStatus = recoveryStatus;
    }

    public String getRecoveryDetails() {
        return recoveryDetails;
    }

    public void setRecoveryDetails(String recoveryDetails) {
        this.recoveryDetails = recoveryDetails;
    }

    public String getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(String cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public Long getExecutionDurationMs() {
        return executionDurationMs;
    }

    public void setExecutionDurationMs(Long executionDurationMs) {
        this.executionDurationMs = executionDurationMs;
    }

    @Override
    public String toString() {
        return "HealingAuditRecord{" +
                "id='" + id + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", actionType=" + actionType +
                ", triggerReason='" + triggerReason + '\'' +
                ", attemptCount=" + attemptCount +
                ", actionStatus=" + actionStatus +
                ", recoveryStatus='" + recoveryStatus + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
