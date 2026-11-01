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

    @Value("${anomaly.confirmation.mode:swea}")
    private String confirmationMode;

    @Value("${anomaly.confirmation.swea.a:1.0}")
    private double sweaA;

    @Value("${anomaly.confirmation.swea.b:2.0}")
    private double sweaB;

    @Value("${anomaly.confirmation.swea.lambda-a:0.9}")
    private double sweaLambdaA;

    @Value("${anomaly.confirmation.swea.lambda-n:0.7}")
    private double sweaLambdaN;

    @Value("${anomaly.confirmation.swea.delta:0.5}")
    private double sweaDelta;

    @Value("${anomaly.confirmation.swea.theta:3.0}")
    private double sweaTheta;

    @Value("${anomaly.confirmation.swea.s-ref:0.1}")
    private double sweaSRef;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private HealingEngineService healingEngineService;

    private final Map<String, ServiceAnomalyState> states = new ConcurrentHashMap<>();

    /**
     * Processes an incoming ML prediction through the confirmation state machine (SWEA or Fixed).
     */
    public void recordEvaluation(MlPredictionResponse prediction) {
        if (!enabled || prediction == null || prediction.getService() == null) {
            return;
        }

        String serviceName = prediction.getService();
        ServiceAnomalyState state = states.computeIfAbsent(serviceName, s -> new ServiceAnomalyState(s, windowSize));

        state.recordEvaluation(prediction, windowSize);
        state.setConfirmationMode(confirmationMode);
        state.setEvidenceThreshold(sweaTheta);

        boolean isAnomaly = Boolean.TRUE.equals(prediction.getIsAnomaly());
        AnomalyStatus currentStatus = state.getStatus();
        AnomalyStatus nextStatus = currentStatus;

        if ("swea".equalsIgnoreCase(confirmationMode)) {
            // Severity-Weighted Evidence Accumulation (SWEA - Novelty N1)
            double score = prediction.getAnomalyScore() != null ? prediction.getAnomalyScore() : 0.0;
            double currentEvidence = state.getEvidenceScore();
            if (isAnomaly) {
                double r = Math.abs(score) / Math.max(0.001, sweaSRef);
                double gain = sweaA + (sweaB * r);
                currentEvidence = (sweaLambdaA * currentEvidence) + gain;
            } else {
                currentEvidence = Math.max(0.0, (sweaLambdaN * currentEvidence) - sweaDelta);
            }
            state.setEvidenceScore(Math.round(currentEvidence * 1000.0) / 1000.0);

            switch (currentStatus) {
                case HEALTHY:
                    if (currentEvidence >= sweaTheta) {
                        nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                        state.setConfirmedTimestamp(prediction.getTimestamp());
                        log.warn("[ANOMALY CONFIRMED (SWEA)] Service '{}' crossed evidence threshold ({}/{}). Score={}. Escalating to Healing Engine.",
                                serviceName, state.getEvidenceScore(), sweaTheta, score);
                    } else if (currentEvidence > sweaDelta || isAnomaly) {
                        nextStatus = AnomalyStatus.SUSPECT;
                        log.info("[ANOMALY SUSPECT (SWEA)] Service '{}' accumulated evidence ({}/{}). Awaiting confirmation.",
                                serviceName, state.getEvidenceScore(), sweaTheta);
                    }
                    break;

                case SUSPECT:
                    if (currentEvidence >= sweaTheta) {
                        nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                        state.setConfirmedTimestamp(prediction.getTimestamp());
                        log.warn("[ANOMALY CONFIRMED (SWEA)] Service '{}' crossed evidence threshold ({}/{}). Score={}. Escalating to Healing Engine.",
                                serviceName, state.getEvidenceScore(), sweaTheta, score);
                    } else if (currentEvidence <= 0.0) {
                        nextStatus = AnomalyStatus.HEALTHY;
                        log.info("[ANOMALY CLEARED (SWEA)] Service '{}' evidence decayed to 0. Restored to HEALTHY.", serviceName);
                    }
                    break;

                case CONFIRMED_ANOMALY:
                    if (!isAnomaly && (currentEvidence <= (sweaTheta * 0.3) || state.getConsecutiveNormal() >= recoveryCycles)) {
                        nextStatus = AnomalyStatus.RECOVERING;
                        log.info("[ANOMALY RECOVERING (SWEA)] Service '{}' evidence decayed below recovery limit (E={}). Transitioning to RECOVERING.",
                                serviceName, state.getEvidenceScore());
                    }
                    break;

                case RECOVERING:
                    if (isAnomaly && currentEvidence >= (sweaTheta * 0.7)) {
                        nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                        log.warn("[ANOMALY RELAPSE (SWEA)] Service '{}' re-accumulated evidence ({}/{}). Returning to CONFIRMED_ANOMALY.",
                                serviceName, state.getEvidenceScore(), sweaTheta);
                    } else if (currentEvidence == 0.0 || state.getConsecutiveNormal() >= (recoveryCycles + 1)) {
                        nextStatus = AnomalyStatus.HEALTHY;
                        state.setFirstAnomalyTimestamp(null);
                        state.setConfirmedTimestamp(null);
                        log.info("[ANOMALY RECOVERED (SWEA)] Service '{}' has completed recovery. Restored to HEALTHY baseline.", serviceName);
                    }
                    break;
            }
        } else {
            // Fixed K-of-N Confirmation Baseline (B0)
            switch (currentStatus) {
                case HEALTHY:
                    if (isAnomaly) {
                        if (state.getConsecutiveAnomalies() >= consecutiveRequired || state.getAnomaliesInWindow() >= thresholdCount) {
                            nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                            state.setConfirmedTimestamp(prediction.getTimestamp());
                            log.warn("[ANOMALY CONFIRMED] Service '{}' reached confirmed anomaly threshold ({}/{} in window, consecutive: {}). Escalating.",
                                    serviceName, state.getAnomaliesInWindow(), windowSize, state.getConsecutiveAnomalies());
                        } else {
                            nextStatus = AnomalyStatus.SUSPECT;
                            log.info("[ANOMALY SUSPECT] Service '{}' single anomalous observation detected ({}/{} in window). Potential jitter/GC pause.",
                                    serviceName, state.getAnomaliesInWindow(), windowSize);
                        }
                    }
                    break;

                case SUSPECT:
                    if (isAnomaly) {
                        if (state.getConsecutiveAnomalies() >= consecutiveRequired || state.getAnomaliesInWindow() >= thresholdCount) {
                            nextStatus = AnomalyStatus.CONFIRMED_ANOMALY;
                            state.setConfirmedTimestamp(prediction.getTimestamp());
                            log.warn("[ANOMALY CONFIRMED] Service '{}' confirmed sustained degradation ({}/{} in window, consecutive: {}). Escalating.",
                                    serviceName, state.getAnomaliesInWindow(), windowSize, state.getConsecutiveAnomalies());
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
        }

        state.setStatus(nextStatus);

        if (nextStatus == AnomalyStatus.CONFIRMED_ANOMALY && healingEngineService != null) {
            try {
                healingEngineService.healService(state, false);
            } catch (Exception e) {
                log.error("Failed to execute autonomous healing for service {}: {}", serviceName, e.getMessage());
            }
        }
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
    public void resetState(String serviceName) {
        ServiceAnomalyState state = states.get(serviceName);
        if (state != null) {
            state.reset();
        }
    }

    /**
     * Resets confirmation states for all services.
     */
    public void resetAll() {
        for (ServiceAnomalyState s : states.values()) {
            s.reset();
        }
        log.info("Reset all anomaly confirmation states.");
    }
}
