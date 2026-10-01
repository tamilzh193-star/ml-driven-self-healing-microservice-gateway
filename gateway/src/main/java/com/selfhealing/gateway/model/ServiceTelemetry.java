package com.selfhealing.gateway.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ServiceTelemetry {
    private String timestamp;
    private String serviceName;
    private Double cpu;
    private Double memory;
    private Double latency;
    private Double errorRate;
    private Double requestRate;
    private String healthStatus;

    public ServiceTelemetry() {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
    }

    public ServiceTelemetry(String serviceName, Double cpu, Double memory, Double latency,
                            Double errorRate, Double requestRate, String healthStatus) {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        this.serviceName = serviceName;
        this.cpu = cpu;
        this.memory = memory;
        this.latency = latency;
        this.errorRate = errorRate;
        this.requestRate = requestRate;
        this.healthStatus = healthStatus;
    }

    public String toCsvRow() {
        return String.format("%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%s",
                timestamp,
                serviceName,
                cpu != null ? cpu : 0.0,
                memory != null ? memory : 0.0,
                latency != null ? latency : 0.0,
                errorRate != null ? errorRate : 0.0,
                requestRate != null ? requestRate : 0.0,
                healthStatus != null ? healthStatus : "UNKNOWN");
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Double getCpu() {
        return cpu;
    }

    public void setCpu(Double cpu) {
        this.cpu = cpu;
    }

    public Double getMemory() {
        return memory;
    }

    public void setMemory(Double memory) {
        this.memory = memory;
    }

    public Double getLatency() {
        return latency;
    }

    public void setLatency(Double latency) {
        this.latency = latency;
    }

    public Double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(Double errorRate) {
        this.errorRate = errorRate;
    }

    public Double getRequestRate() {
        return requestRate;
    }

    public void setRequestRate(Double requestRate) {
        this.requestRate = requestRate;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }
}
