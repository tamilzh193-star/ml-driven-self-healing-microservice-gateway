package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.HealingActionType;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Rule-based remediation selector mapping confirmed telemetry signatures to targeted healing actions.
 */
@Service
public class HealingStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(HealingStrategySelector.class);

    public static class RemediationDecision {
        private final HealingActionType actionType;
        private final String reason;

        public RemediationDecision(HealingActionType actionType, String reason) {
            this.actionType = actionType;
            this.reason = reason;
        }

        public HealingActionType getActionType() {
            return actionType;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Determines the optimal healing action based on confirmed telemetry feature signatures.
     */
    public RemediationDecision selectRemediation(ServiceAnomalyState state) {
        String service = state.getServiceName();
        MlPredictionResponse latest = state.getLatestPrediction();

        if (latest == null || latest.getFeatures() == null) {
            log.warn("No telemetry features available for {}. Defaulting to RESTART remediation.", service);
            return new RemediationDecision(HealingActionType.RESTART, "Default recovery action for unspecified anomaly signature");
        }

        Map<String, Object> f = latest.getFeatures();
        double errorRate = getDouble(f, "error_rate");
        double latency = getDouble(f, "latency");
        double memory = getDouble(f, "memory");
        double requestRate = getDouble(f, "request_rate");
        double cpu = getDouble(f, "cpu");

        // 1. Critical Failure / Memory / HTTP 500 error rate -> RESTART
        if (errorRate >= 40.0) {
            return new RemediationDecision(
                    HealingActionType.RESTART,
                    String.format("Critical server error rate (%.1f%%) detected; initiating service restart to clear faulted state", errorRate)
            );
        }

        if (memory >= 85.0) {
            return new RemediationDecision(
                    HealingActionType.RESTART,
                    String.format("JVM heap memory saturation (%.1f%%) detected; initiating service restart to prevent OutOfMemoryError", memory)
            );
        }

        // 2. Traffic Surge / High Concurrency Overload -> RATE_LIMIT
        if (requestRate >= 3.0 || (cpu >= 70.0 && requestRate >= 2.0)) {
            return new RemediationDecision(
                    HealingActionType.RATE_LIMIT,
                    String.format("Traffic surge (%.1f req/s, CPU: %.1f%%) detected; activating dynamic gateway rate limiting to prevent collapse", requestRate, cpu)
            );
        }

        // 3. High Latency / Service Degradation -> TRAFFIC_REDIRECTION
        if (latency >= 800.0) {
            return new RemediationDecision(
                    HealingActionType.TRAFFIC_REDIRECTION,
                    String.format("Severe latency degradation (%.1f ms) exceeding SLA; activating traffic redirection and fallback mode", latency)
            );
        }

        // 4. Fallback signature analysis: pick dominant anomaly component
        if (latency > 200.0) {
            return new RemediationDecision(
                    HealingActionType.TRAFFIC_REDIRECTION,
                    String.format("Elevated response latency (%.1f ms); rerouting traffic to safe fallback", latency)
            );
        }

        if (cpu > 60.0) {
            return new RemediationDecision(
                    HealingActionType.RATE_LIMIT,
                    String.format("Elevated CPU utilization (%.1f%%); applying traffic rate limiting", cpu)
            );
        }

        return new RemediationDecision(
                HealingActionType.RESTART,
                String.format("Sustained anomaly confirmed (score: %.4f); triggering service restart", state.getAverageAnomalyScore())
        );
    }

    private double getDouble(Map<String, Object> map, String key) {
        if (map == null) return 0.0;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        } else if (val != null) {
            try {
                return Double.parseDouble(val.toString());
            } catch (Exception ignored) {
            }
        }
        return 0.0;
    }
}
