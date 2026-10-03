package com.selfhealing.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.selfhealing.gateway.dto.MlPredictionResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ServiceAnomalyState {

    private String serviceName;
    private AnomalyStatus status;
    private int consecutiveAnomalies;
    private int consecutiveNormal;
    private int anomaliesInWindow;
    private int windowSize;
    private double averageAnomalyScore;
    private String firstAnomalyTimestamp;
    private String confirmedTimestamp;
    private String lastEvaluationTimestamp;
    private MlPredictionResponse latestPrediction;

    @JsonIgnore
    private final Deque<MlPredictionResponse> slidingWindow = new ArrayDeque<>();

    public ServiceAnomalyState() {
        this.status = AnomalyStatus.HEALTHY;
    }

    public ServiceAnomalyState(String serviceName, int windowSize) {
        this.serviceName = serviceName;
        this.status = AnomalyStatus.HEALTHY;
        this.windowSize = windowSize;
        this.consecutiveAnomalies = 0;
        this.consecutiveNormal = 0;
        this.anomaliesInWindow = 0;
        this.averageAnomalyScore = 0.0;
    }

    public synchronized void recordEvaluation(MlPredictionResponse prediction, int windowCapacity) {
        this.latestPrediction = prediction;
        this.lastEvaluationTimestamp = prediction.getTimestamp();
        this.windowSize = windowCapacity;

        slidingWindow.addLast(prediction);
        while (slidingWindow.size() > windowCapacity) {
            slidingWindow.pollFirst();
        }

        boolean isAno = Boolean.TRUE.equals(prediction.getIsAnomaly());
        if (isAno) {
            consecutiveAnomalies++;
            consecutiveNormal = 0;
            if (firstAnomalyTimestamp == null) {
                firstAnomalyTimestamp = prediction.getTimestamp();
            }
        } else {
            consecutiveNormal++;
            consecutiveAnomalies = 0;
        }

        // Recalculate anomalies in window and average score
        int count = 0;
        double scoreSum = 0.0;
        for (MlPredictionResponse p : slidingWindow) {
            if (Boolean.TRUE.equals(p.getIsAnomaly())) {
                count++;
            }
            if (p.getAnomalyScore() != null) {
                scoreSum += p.getAnomalyScore();
            }
        }
        this.anomaliesInWindow = count;
        this.averageAnomalyScore = slidingWindow.isEmpty() ? 0.0 : Math.round((scoreSum / slidingWindow.size()) * 10000.0) / 10000.0;
    }

    public synchronized void reset() {
        slidingWindow.clear();
        this.status = AnomalyStatus.HEALTHY;
        this.consecutiveAnomalies = 0;
        this.consecutiveNormal = 0;
        this.anomaliesInWindow = 0;
        this.averageAnomalyScore = 0.0;
        this.firstAnomalyTimestamp = null;
        this.confirmedTimestamp = null;
    }

    public synchronized List<MlPredictionResponse> getWindowHistory() {
        return new ArrayList<>(slidingWindow);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public AnomalyStatus getStatus() {
        return status;
    }

    public void setStatus(AnomalyStatus status) {
        this.status = status;
    }

    public int getConsecutiveAnomalies() {
        return consecutiveAnomalies;
    }

    public void setConsecutiveAnomalies(int consecutiveAnomalies) {
        this.consecutiveAnomalies = consecutiveAnomalies;
    }

    public int getConsecutiveNormal() {
        return consecutiveNormal;
    }

    public void setConsecutiveNormal(int consecutiveNormal) {
        this.consecutiveNormal = consecutiveNormal;
    }

    public int getAnomaliesInWindow() {
        return anomaliesInWindow;
    }

    public void setAnomaliesInWindow(int anomaliesInWindow) {
        this.anomaliesInWindow = anomaliesInWindow;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public double getAverageAnomalyScore() {
        return averageAnomalyScore;
    }

    public void setAverageAnomalyScore(double averageAnomalyScore) {
        this.averageAnomalyScore = averageAnomalyScore;
    }

    public String getFirstAnomalyTimestamp() {
        return firstAnomalyTimestamp;
    }

    public void setFirstAnomalyTimestamp(String firstAnomalyTimestamp) {
        this.firstAnomalyTimestamp = firstAnomalyTimestamp;
    }

    public String getConfirmedTimestamp() {
        return confirmedTimestamp;
    }

    public void setConfirmedTimestamp(String confirmedTimestamp) {
        this.confirmedTimestamp = confirmedTimestamp;
    }

    public String getLastEvaluationTimestamp() {
        return lastEvaluationTimestamp;
    }

    public void setLastEvaluationTimestamp(String lastEvaluationTimestamp) {
        this.lastEvaluationTimestamp = lastEvaluationTimestamp;
    }

    public MlPredictionResponse getLatestPrediction() {
        return latestPrediction;
    }

    public void setLatestPrediction(MlPredictionResponse latestPrediction) {
        this.latestPrediction = latestPrediction;
    }
}
