package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.HealingActionType;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


@Service
public class HealingStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(HealingStrategySelector.class);

    @Value("${healing.policy.mode:thompson}")
    private String policyMode; // "thompson" or "rule"

    @Value("${healing.safety.restart-cooldown-ms:600000}")
    private long restartCooldownMs; // 10 minutes

    @Value("${healing.safety.max-concurrent-restarts:1}")
    private int maxConcurrentRestarts;

    @Autowired(required = false)
    private DatabasePersistenceService databasePersistenceService;

    // Last restart timestamp per service: serviceName -> epochMillis
    private final Map<String, Long> lastRestartTimeMap = new ConcurrentHashMap<>();

    // Concurrently active restarts across cluster
    private final AtomicInteger activeRestarts = new AtomicInteger(0);

    // Random generator for Thompson sampling
    private final Random random = new Random();

    // Contextual policy posteriors: contextKey -> (actionType -> ActionPosterior)
    private final Map<String, Map<HealingActionType, ActionPosterior>> policyMap = new ConcurrentHashMap<>();

    public static class RemediationDecision {
        private final HealingActionType actionType;
        private final String reason;
        private final String contextKey;

        public RemediationDecision(HealingActionType actionType, String reason, String contextKey) {
            this.actionType = actionType;
            this.reason = reason;
            this.contextKey = contextKey;
        }

        public HealingActionType getActionType() {
            return actionType;
        }

        public String getReason() {
            return reason;
        }

        public String getContextKey() {
            return contextKey;
        }
    }

    public static class ActionPosterior {
        private double alpha;
        private double beta;
        private int successes;
        private int failures;

        public ActionPosterior(double alpha, double beta) {
            this(alpha, beta, 0, 0);
        }

        public ActionPosterior(double alpha, double beta, int successes, int failures) {
            this.alpha = alpha;
            this.beta = beta;
            this.successes = Math.max(0, successes);
            this.failures = Math.max(0, failures);
        }

        public synchronized void update(boolean success) {
            if (success) {
                alpha += 1.0;
                successes++;
            } else {
                beta += 1.0;
                failures++;
            }
        }

        public synchronized double getAlpha() {
            return alpha;
        }

        public synchronized double getBeta() {
            return beta;
        }

        public synchronized int getSuccesses() {
            return successes;
        }

        public synchronized int getFailures() {
            return failures;
        }

        public synchronized double getExpectedValue() {
            return alpha / (alpha + beta);
        }

        public synchronized double sample(Random random) {
            return sampleBeta(alpha, beta, random);
        }
    }

    public HealingStrategySelector() {
        initDefaultPriors();
    }

    public void setDatabasePersistenceService(DatabasePersistenceService databasePersistenceService) {
        this.databasePersistenceService = databasePersistenceService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        loadPersistedPoliciesFromDatabase();
    }

    /**
     * Restores persisted Thompson sampling posterior statistics from the database.
     * Validates context keys, action types, positive finite alpha/beta values, and non-negative counts.
     * Skips invalid records with a warning.
     * Preserves default priors for missing records or if database is unavailable.
     */
    public synchronized void loadPersistedPoliciesFromDatabase() {
        if (databasePersistenceService == null) {
            log.info("[POLICY RESTORE] DatabasePersistenceService is not available; retaining default priors.");
            return;
        }

        try {
            List<Map<String, Object>> rows = databasePersistenceService.loadAllPolicyStats();
            if (rows == null || rows.isEmpty()) {
                log.info("[POLICY RESTORE] No persisted policy statistics found in database; using default priors.");
                return;
            }

            int restoredCount = 0;
            int skippedCount = 0;

            for (Map<String, Object> row : rows) {
                String contextKey = row.get("context_key") != null ? row.get("context_key").toString().trim() : null;
                String actionTypeStr = row.get("action_type") != null ? row.get("action_type").toString().trim() : null;

                if (contextKey == null || contextKey.isEmpty() || actionTypeStr == null || actionTypeStr.isEmpty()) {
                    log.warn("[POLICY RESTORE] Skipping record with missing context_key or action_type: {}", row);
                    skippedCount++;
                    continue;
                }

                HealingActionType actionType;
                try {
                    actionType = HealingActionType.valueOf(actionTypeStr);
                } catch (IllegalArgumentException e) {
                    log.warn("[POLICY RESTORE] Skipping record with unknown action_type '{}' in context '{}'", actionTypeStr, contextKey);
                    skippedCount++;
                    continue;
                }

                double alpha = parseDouble(row.get("alpha"), -1.0);
                double beta = parseDouble(row.get("beta"), -1.0);
                int successes = parseInt(row.get("successes"), -1);
                int failures = parseInt(row.get("failures"), -1);

                // Validation rules: alpha and beta must be finite and strictly positive; counts must be non-negative
                if (Double.isNaN(alpha) || Double.isInfinite(alpha) || alpha <= 0.0 ||
                    Double.isNaN(beta) || Double.isInfinite(beta) || beta <= 0.0 ||
                    successes < 0 || failures < 0) {
                    log.warn("[POLICY RESTORE] Skipping invalid parameters for context '{}' action '{}': alpha={}, beta={}, successes={}, failures={}",
                            contextKey, actionType, alpha, beta, successes, failures);
                    skippedCount++;
                    continue;
                }

                Map<HealingActionType, ActionPosterior> actionMap = policyMap.computeIfAbsent(contextKey, k -> new ConcurrentHashMap<>());
                actionMap.put(actionType, new ActionPosterior(alpha, beta, successes, failures));
                restoredCount++;
            }

            log.info("[POLICY RESTORE] Completed policy restoration from database: restored {} posterior entries, skipped {} invalid records.",
                    restoredCount, skippedCount);

        } catch (Exception e) {
            log.warn("[POLICY RESTORE] Failed to load policy statistics from database (safe fallback to defaults): {}", e.getMessage());
        }
    }

    private double parseDouble(Object val, double defaultValue) {
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        } else if (val != null) {
            try {
                return Double.parseDouble(val.toString().trim());
            } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private int parseInt(Object val, int defaultValue) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        } else if (val != null) {
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private void initDefaultPriors() {
        // Feature: latency
        seedPriors("latency:mild", 2.0, 2.0, 5.0, 1.0, 1.0, 3.0);
        seedPriors("latency:severe", 2.0, 2.0, 5.0, 1.0, 1.0, 3.0);

        // Feature: error_rate
        seedPriors("error_rate:mild", 1.0, 3.0, 4.0, 1.0, 2.0, 2.0);
        seedPriors("error_rate:severe", 1.0, 4.0, 2.0, 3.0, 5.0, 1.0);

        // Feature: cpu
        seedPriors("cpu:mild", 5.0, 1.0, 2.0, 2.0, 1.0, 3.0);
        seedPriors("cpu:severe", 5.0, 1.0, 2.0, 2.0, 1.0, 3.0);

        // Feature: memory
        seedPriors("memory:mild", 1.0, 4.0, 2.0, 3.0, 5.0, 1.0);
        seedPriors("memory:severe", 1.0, 5.0, 1.0, 4.0, 6.0, 1.0);

        // Feature: request_rate
        seedPriors("request_rate:mild", 5.0, 1.0, 2.0, 2.0, 1.0, 3.0);
        seedPriors("request_rate:severe", 5.0, 1.0, 2.0, 2.0, 1.0, 3.0);

        // General fallback
        seedPriors("general:mild", 2.0, 2.0, 2.0, 2.0, 2.0, 2.0);
        seedPriors("general:severe", 2.0, 2.0, 2.0, 2.0, 3.0, 1.0);
    }

    private void seedPriors(String contextKey,
                            double rateLimitAlpha, double rateLimitBeta,
                            double redirAlpha, double redirBeta,
                            double restartAlpha, double restartBeta) {
        Map<HealingActionType, ActionPosterior> map = new ConcurrentHashMap<>();
        map.put(HealingActionType.RATE_LIMIT, new ActionPosterior(rateLimitAlpha, rateLimitBeta));
        map.put(HealingActionType.TRAFFIC_REDIRECTION, new ActionPosterior(redirAlpha, redirBeta));
        map.put(HealingActionType.RESTART, new ActionPosterior(restartAlpha, restartBeta));
        policyMap.put(contextKey, map);
    }

    /**
     * Determines the optimal healing action using contextual Thompson sampling or rule fallback,
     * applying safety constraints and ladder escalation.
     */
    public RemediationDecision selectRemediation(ServiceAnomalyState state) {
        String service = state.getServiceName();
        MlPredictionResponse latest = state.getLatestPrediction();

        String dominantFeature = resolveDominantFeature(state, latest);
        String severity = (state.getAverageAnomalyScore() <= -0.15) ? "severe" : "mild";
        String contextKey = dominantFeature + ":" + severity;

        // Ladder Escalation: If repeated attempts, escalate to higher tier
        int consecutiveAnomalies = state.getConsecutiveAnomalies();
        if (consecutiveAnomalies >= 4) {
            log.info("[ESCALATION LADDER] Service '{}' in sustained degradation (consecutive: {}). Escalating to L3 RESTART.",
                    service, consecutiveAnomalies);
            if (canRestart(service)) {
                return new RemediationDecision(
                        HealingActionType.RESTART,
                        String.format("Escalation ladder tier-3: sustained anomaly across %d consecutive evaluations", consecutiveAnomalies),
                        contextKey
                );
            }
        }

        // Rule-based mode fallback (B1 baseline)
        if ("rule".equalsIgnoreCase(policyMode)) {
            return selectRuleBasedRemediation(state, dominantFeature, contextKey);
        }

        // Contextual Thompson Sampling Selection (Novelty N3)
        Map<HealingActionType, ActionPosterior> posteriors = policyMap.computeIfAbsent(contextKey, k -> {
            Map<HealingActionType, ActionPosterior> map = new ConcurrentHashMap<>();
            map.put(HealingActionType.RATE_LIMIT, new ActionPosterior(2.0, 2.0));
            map.put(HealingActionType.TRAFFIC_REDIRECTION, new ActionPosterior(2.0, 2.0));
            map.put(HealingActionType.RESTART, new ActionPosterior(2.0, 2.0));
            return map;
        });

        HealingActionType bestAction = HealingActionType.TRAFFIC_REDIRECTION;
        double bestSample = -1.0;

        for (Map.Entry<HealingActionType, ActionPosterior> entry : posteriors.entrySet()) {
            HealingActionType action = entry.getKey();
            ActionPosterior posterior = entry.getValue();

            // Safety guard: filter out RESTART if cooldown or concurrency budget violated
            if (action == HealingActionType.RESTART && !canRestart(service)) {
                log.info("[SAFETY GUARD] RESTART action suppressed for service '{}' by cooldown or cluster concurrency limit.", service);
                continue;
            }

            double sample = posterior.sample(random);
            if (sample > bestSample) {
                bestSample = sample;
                bestAction = action;
            }
        }

        String reason = buildDecisionReason(bestAction, dominantFeature, state);
        log.info("[THOMPSON SAMPLING] Context '{}' -> Selected {} (sample: {:.3f}, E[V]: {:.3f}) for service '{}'",
                contextKey, bestAction, bestSample, posteriors.get(bestAction).getExpectedValue(), service);

        return new RemediationDecision(bestAction, reason, contextKey);
    }

    private boolean canRestart(String service) {
        long now = System.currentTimeMillis();
        Long lastRestart = lastRestartTimeMap.get(service);
        if (lastRestart != null && (now - lastRestart) < restartCooldownMs) {
            return false;
        }
        if (activeRestarts.get() >= maxConcurrentRestarts) {
            return false;
        }
        return true;
    }

    public void markRestartStarted(String service) {
        lastRestartTimeMap.put(service, System.currentTimeMillis());
        activeRestarts.incrementAndGet();
    }

    public void markRestartFinished(String service) {
        activeRestarts.updateAndGet(c -> Math.max(0, c - 1));
    }

    /**
     * Updates policy posteriors based on verified recovery outcome.
     */
    public void recordOutcome(String contextKey, HealingActionType actionType, boolean success) {
        if (contextKey == null || actionType == null) {
            return;
        }

        Map<HealingActionType, ActionPosterior> posteriors = policyMap.get(contextKey);
        if (posteriors != null) {
            ActionPosterior posterior = posteriors.get(actionType);
            if (posterior != null) {
                posterior.update(success);
                log.info("[OUTCOME LEARNING] Context '{}' Action '{}' -> Outcome: {} (New alpha: {}, beta: {}, E[V]: {:.3f})",
                        contextKey, actionType, success ? "SUCCESS" : "FAILURE",
                        posterior.getAlpha(), posterior.getBeta(), posterior.getExpectedValue());

                // Persist asynchronously to database
                if (databasePersistenceService != null) {
                    databasePersistenceService.persistPolicyStatAsync(
                            contextKey, actionType.name(),
                            posterior.getAlpha(), posterior.getBeta(),
                            posterior.getSuccesses(), posterior.getFailures()
                    ).subscribe();
                }
            }
        }
    }

    private String resolveDominantFeature(ServiceAnomalyState state, MlPredictionResponse latest) {
        if (state.getDominantFeature() != null) {
            return state.getDominantFeature();
        }
        if (latest != null && latest.getDominantFeature() != null) {
            return latest.getDominantFeature();
        }
        if (latest != null && latest.getFeatures() != null) {
            Map<String, Object> f = latest.getFeatures();
            double latency = getDouble(f, "latency");
            double errorRate = getDouble(f, "error_rate");
            double memory = getDouble(f, "memory");
            double cpu = getDouble(f, "cpu");
            double requestRate = getDouble(f, "request_rate");

            if (memory >= 80.0) return "memory";
            if (errorRate >= 20.0) return "error_rate";
            if (latency >= 300.0) return "latency";
            if (cpu >= 70.0) return "cpu";
            if (requestRate >= 2.0) return "request_rate";
        }
        return "general";
    }

    private RemediationDecision selectRuleBasedRemediation(ServiceAnomalyState state, String dominantFeature, String contextKey) {
        MlPredictionResponse latest = state.getLatestPrediction();
        Map<String, Object> f = (latest != null && latest.getFeatures() != null) ? latest.getFeatures() : Collections.emptyMap();
        double errorRate = getDouble(f, "error_rate");
        double latency = getDouble(f, "latency");
        double memory = getDouble(f, "memory");
        double requestRate = getDouble(f, "request_rate");
        double cpu = getDouble(f, "cpu");

        if (errorRate >= 40.0) {
            return new RemediationDecision(HealingActionType.RESTART,
                    String.format("Critical server error rate (%.1f%%) detected; initiating service restart to clear faulted state", errorRate), contextKey);
        }
        if (memory >= 85.0) {
            return new RemediationDecision(HealingActionType.RESTART,
                    String.format("JVM heap memory saturation (%.1f%%) detected; initiating service restart to prevent OutOfMemoryError", memory), contextKey);
        }
        if (requestRate >= 3.0 || (cpu >= 70.0 && requestRate >= 2.0)) {
            return new RemediationDecision(HealingActionType.RATE_LIMIT,
                    String.format("Traffic surge (%.1f req/s, CPU: %.1f%%) detected; activating dynamic gateway rate limiting to prevent collapse", requestRate, cpu), contextKey);
        }
        if (latency >= 800.0) {
            return new RemediationDecision(HealingActionType.TRAFFIC_REDIRECTION,
                    String.format("Severe latency degradation (%.1f ms) exceeding SLA; activating traffic redirection and fallback mode", latency), contextKey);
        }
        if (latency > 200.0) {
            return new RemediationDecision(HealingActionType.TRAFFIC_REDIRECTION,
                    String.format("Elevated response latency (%.1f ms); rerouting traffic to safe fallback", latency), contextKey);
        }
        if (cpu > 60.0) {
            return new RemediationDecision(HealingActionType.RATE_LIMIT,
                    String.format("Elevated CPU utilization (%.1f%%); applying traffic rate limiting", cpu), contextKey);
        }
        return new RemediationDecision(HealingActionType.RESTART, "Default rule-based recovery action", contextKey);
    }

    private String buildDecisionReason(HealingActionType action, String dominantFeature, ServiceAnomalyState state) {
        MlPredictionResponse latest = state.getLatestPrediction();
        Map<String, Object> f = (latest != null && latest.getFeatures() != null) ? latest.getFeatures() : Collections.emptyMap();
        double latency = getDouble(f, "latency");
        double errorRate = getDouble(f, "error_rate");
        double memory = getDouble(f, "memory");
        double cpu = getDouble(f, "cpu");
        double requestRate = getDouble(f, "request_rate");

        switch (action) {
            case RATE_LIMIT:
                return String.format("Traffic surge (%.1f req/s, CPU: %.1f%%) detected; activating dynamic gateway rate limiting to prevent collapse",
                        requestRate, cpu);
            case TRAFFIC_REDIRECTION:
                if (latency >= 800.0) {
                    return String.format("Severe latency degradation (%.1f ms) exceeding SLA; activating traffic redirection and fallback mode", latency);
                } else {
                    return String.format("Elevated response latency (%.1f ms); rerouting traffic to safe fallback", latency);
                }
            case RESTART:
                if (memory >= 85.0) {
                    return String.format("JVM heap memory saturation (%.1f%%) detected; initiating service restart to prevent OutOfMemoryError", memory);
                } else if (errorRate >= 40.0) {
                    return String.format("Critical server error rate (%.1f%%) detected; initiating service restart to clear faulted state", errorRate);
                } else {
                    return String.format("Sustained anomaly confirmed (dominant feature: %s); initiating service restart", dominantFeature);
                }
            default:
                return "Autonomous healing policy selected " + action;
        }
    }

    public Map<String, Map<String, Map<String, Object>>> getPolicyStatsSummary() {
        Map<String, Map<String, Map<String, Object>>> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Map<HealingActionType, ActionPosterior>> cEntry : policyMap.entrySet()) {
            Map<String, Map<String, Object>> actMap = new LinkedHashMap<>();
            for (Map.Entry<HealingActionType, ActionPosterior> aEntry : cEntry.getValue().entrySet()) {
                ActionPosterior p = aEntry.getValue();
                actMap.put(aEntry.getKey().name(), Map.of(
                        "alpha", p.getAlpha(),
                        "beta", p.getBeta(),
                        "expectedValue", Math.round(p.getExpectedValue() * 1000.0) / 1000.0,
                        "successes", p.getSuccesses(),
                        "failures", p.getFailures()
                ));
            }
            summary.put(cEntry.getKey(), actMap);
        }
        return summary;
    }

    private double getDouble(Map<String, Object> map, String key) {
        if (map == null) return 0.0;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        } else if (val != null) {
            try {
                return Double.parseDouble(val.toString());
            } catch (Exception ignored) {}
        }
        return 0.0;
    }

    /**
     * Exact Beta sampler using Marsaglia-Tsang Gamma generator.
     */
    public static double sampleBeta(double alpha, double beta, Random rng) {
        double x = sampleGamma(alpha, rng);
        double y = sampleGamma(beta, rng);
        if (x + y <= 0.0) {
            return 0.5;
        }
        return x / (x + y);
    }

    private static double sampleGamma(double shape, Random rng) {
        if (shape < 1.0) {
            // Johnk's generator for shape < 1
            double u = rng.nextDouble();
            return sampleGamma(shape + 1.0, rng) * Math.pow(u, 1.0 / shape);
        }
        // Marsaglia and Tsang method (2000) for shape >= 1
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double z = rng.nextGaussian();
            double v = 1.0 + c * z;
            if (v <= 0.0) continue;
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * z * z * z * z) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * z * z + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }
}
