package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.AnomalyStatus;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AnomalyConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyConfirmationService.class);

    @Value("${anomaly.confirmation.enabled:true}")
    private boolean enabled;

    @Value("${anomaly.confirmation.window-size:5}")
    private int windowSize;

    @Value("${anomaly.confirmation.threshold-count:3}")
    private int thresholdCount;

    @Value("${anomaly.confirmation.consecutive-required:3}")
    private int consecutiveRequired;

    @Value("${anomaly.confirmation.recovery-cycles:2}")
    private int recoveryCycles;

    private final Map<String, ServiceAnomalyState> states = new ConcurrentHashMap<>();

    /**
     * Processes an incoming ML prediction through the sliding window confirmation state machine.
     */
    public synchronized void recordEvaluation(MlPredictionResponse prediction) {
        if (!enabled || prediction == null || prediction.getService() == null) {
            return;
        }

        String serviceName = prediction.getService();
        ServiceAnomalyState state = states.computeIfAbsent(serviceName, s -> new ServiceAnomalyState(s, windowSize));

        state.recordEvaluation(prediction, windowSize);

        boolean isAnomaly = Boolean.TRUE.equals(prediction.getIsAnomaly());
        AnomalyStatus currentStatus = state.getStatus();
        AnomalyStatus nextStatus = currentStatus;

        switch (currentStatus) {
            case HEALTHY:
                if (isAnomaly) {
                    if (state.getConsecutiveAnomalies() >= consecutiveRequired || state.getAnomaliesInWindow() >= thresholdCount) {
                        nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                        state.setConfirmedTimestamp(prediction.getTimestamp());
                        log.warn("[ANOMALY CONFIRMED] Service '{}' reached confirmed anomaly threshold ({}/{} in window, consecutive: {}). Anomaly score: {}. Escalating to Healing Engine.",
                                serviceName, state.getAnomaliesInWindow(), windowSize, state.getConsecutiveAnomalies(), prediction.getAnomalyScore());
                    } else {
                        nextStatus = AnomalyStatus.SUSPECT;
                        log.info("[ANOMALY SUSPECT] Service '{}' single anomalous observation detected ({}/{} in window). Potential jitter/GC pause. Awaiting confirmation.",
                                serviceName, state.getAnomaliesInWindow(), windowSize);
                    }
                }
                break;

            case SUSPECT:
                if (isAnomaly) {
                    if (state.getConsecutiveAnomalies() >= consecutiveRequired || state.getAnomaliesInWindow() >= thresholdCount) {
                        nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                        state.setConfirmedTimestamp(prediction.getTimestamp());
                        log.warn("[ANOMALY CONFIRMED] Service '{}' confirmed sustained degradation ({}/{} in window, consecutive: {}). Anomaly score: {}. Escalating to Healing Engine.",
                                serviceName, state.getAnomaliesInWindow(), windowSize, state.getConsecutiveAnomalies(), prediction.getAnomalyScore());
                    } else {
                        log.info("[ANOMALY SUSPECT] Service '{}' remains in suspect state ({}/{} in window, consecutive: {}).",
                                serviceName, state.getAnomaliesInWindow(), windowSize, state.getConsecutiveAnomalies());
                    }
                } else {
                    if (state.getAnomaliesInWindow() == 0) {
                        nextStatus = AnomalyStatus.HEALTHY;
                        log.info("[ANOMALY CLEARED] Service '{}' transient warnings cleared. Restored to HEALTHY.", serviceName);
                    }
                }
                break;

            case CONFIRMED_ANOMALY:
                if (!isAnomaly && state.getConsecutiveNormal() >= recoveryCycles) {
                    nextStatus = AnomalyStatus.RECOVERING;
                    log.info("[ANOMALY RECOVERING] Service '{}' showing {} consecutive normal cycles. Transitioning to RECOVERING.",
                            serviceName, state.getConsecutiveNormal());
                }
                break;

            case RECOVERING:
                if (isAnomaly) {
                    nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                    log.warn("[ANOMALY RELAPSE] Service '{}' relapsed into anomalous behavior during recovery! Escalating back to CONFIRMED_ANOMALY.", serviceName);
                } else if (state.getConsecutiveNormal() >= recoveryCycles + 1) {
                    nextStatus = AnomalyStatus.HEALTHY;
                    state.setFirstAnomalyTimestamp(null);
                    state.setConfirmedTimestamp(null);
                    log.info("[ANOMALY RECOVERED] Service '{}' has completed recovery ({} normal cycles). Restored to HEALTHY baseline.",
                            serviceName, state.getConsecutiveNormal());
                }
                break;
        }

        state.setStatus(nextStatus);
    }

    /**
     * Returns the anomaly confirmation state for a specific service.
     */
    public ServiceAnomalyState getServiceState(String serviceName) {
        return states.computeIfAbsent(serviceName, s -> new ServiceAnomalyState(s, windowSize));
    }

    /**
     * Returns all service anomaly confirmation states.
     */
    public Map<String, ServiceAnomalyState> getAllStates() {
        return new LinkedHashMap<>(states);
    }

    /**
     * Returns the list of services currently in CONFIRMED_ANOMALY state.
     * This directly drives the Autonomous Healing Engine in Phase 12.
     */
    public List<ServiceAnomalyState> getConfirmedAnomalies() {
        return states.values().stream()
                .filter(s -> s.getStatus() == AnomalyStatus.CONFIRMED_ANOMALY)
                .collect(Collectors.toList());
    }

    /**
     * Resets confirmation state for a specific service.
     */
    public synchronized void resetState(String serviceName) {
        ServiceAnomalyState state = states.get(serviceName);
        if (state != null) {
            state.reset();
        }
    }

    /**
     * Resets confirmation states for all services.
     */
    public synchronized void resetAll() {
        for (ServiceAnomalyState s : states.values()) {
            s.reset();
        }
        log.info("Reset all anomaly confirmation states.");
    }
}
