package com.selfhealing.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.selfhealing.gateway.model.ServiceTelemetry;

public class MlPredictionRequest {

    private String service;
    private Double cpu;
    private Double memory;
    private Double latency;

    @JsonProperty("error_rate")
    private Double errorRate;

    @JsonProperty("request_rate")
    private Double requestRate;

    private String timestamp;

    public MlPredictionRequest() {}

    public static MlPredictionRequest from(ServiceTelemetry t) {
        MlPredictionRequest req = new MlPredictionRequest();
        req.setService(t.getServiceName());
        req.setCpu(t.getCpu() != null ? t.getCpu() : 0.0);
        req.setMemory(t.getMemory() != null ? t.getMemory() : 0.0);
        req.setLatency(t.getLatency() != null ? t.getLatency() : 0.0);
        req.setErrorRate(t.getErrorRate() != null ? t.getErrorRate() : 0.0);
        req.setRequestRate(t.getRequestRate() != null ? t.getRequestRate() : 0.0);
        req.setTimestamp(t.getTimestamp());
        return req;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
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

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
