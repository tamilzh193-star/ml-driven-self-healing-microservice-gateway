package com.selfhealing.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class MlPredictionResponse {

    private String service;
    private String prediction; // "NORMAL" or "ANOMALY" or "UNKNOWN"

    @JsonProperty("is_anomaly")
    private Boolean isAnomaly;

    @JsonProperty("anomaly_score")
    private Double anomalyScore;

    private String timestamp;
    private Map<String, Object> features;
    private Map<String, Double> attribution;

    @JsonProperty("dominant_feature")
    private String dominantFeature;

    private String signature;
    private String error;

    public MlPredictionResponse() {}

    public static MlPredictionResponse unavailable(String service, String error) {
        MlPredictionResponse res = new MlPredictionResponse();
        res.setService(service);
        res.setPrediction("UNKNOWN");
        res.setIsAnomaly(false);
        res.setAnomalyScore(0.0);
        res.setError(error);
        return res;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getPrediction() {
        return prediction;
    }

    public void setPrediction(String prediction) {
        this.prediction = prediction;
    }

    public Boolean getIsAnomaly() {
        return isAnomaly;
    }

    public void setIsAnomaly(Boolean anomaly) {
        isAnomaly = anomaly;
    }

    public Double getAnomalyScore() {
        return anomalyScore;
    }

    public void setAnomalyScore(Double anomalyScore) {
        this.anomalyScore = anomalyScore;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Object> features) {
        this.features = features;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Map<String, Double> getAttribution() {
        return attribution;
    }

    public void setAttribution(Map<String, Double> attribution) {
        this.attribution = attribution;
    }

    public String getDominantFeature() {
        return dominantFeature;
    }

    public void setDominantFeature(String dominantFeature) {
        this.dominantFeature = dominantFeature;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }
}
