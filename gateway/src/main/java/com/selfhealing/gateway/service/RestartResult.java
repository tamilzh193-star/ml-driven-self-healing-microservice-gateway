package com.selfhealing.gateway.service;

public class RestartResult {
    private final boolean success;
    private final String mode; // "DOCKER" or "SOFT_RESET"
    private final String details;
    private final long durationMs;

    public RestartResult(boolean success, String mode, String details, long durationMs) {
        this.success = success;
        this.mode = mode;
        this.details = details;
        this.durationMs = durationMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMode() {
        return mode;
    }

    public String getDetails() {
        return details;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return String.format("RestartResult[success=%s, mode=%s, duration=%dms, details=%s]",
                success, mode, durationMs, details);
    }
}
