package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.AnomalyStatus;
import com.selfhealing.gateway.model.HealingActionType;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OutcomeLearningPolicyTest {

    private HealingStrategySelector selector;

    @BeforeEach
    void setUp() {
        selector = new HealingStrategySelector();
        ReflectionTestUtils.setField(selector, "policyMode", "thompson");
        ReflectionTestUtils.setField(selector, "restartCooldownMs", 600000L);
        ReflectionTestUtils.setField(selector, "maxConcurrentRestarts", 1);
    }

    private ServiceAnomalyState createAnomalyState(String service, String dominantFeature, double score, int consecutive) {
        ServiceAnomalyState state = new ServiceAnomalyState(service, 5);
        state.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        state.setConsecutiveAnomalies(consecutive);
        state.setAverageAnomalyScore(score);
        state.setDominantFeature(dominantFeature);

        MlPredictionResponse pred = new MlPredictionResponse();
        pred.setService(service);
        pred.setIsAnomaly(true);
        pred.setDominantFeature(dominantFeature);
        pred.setAnomalyScore(score);
        pred.setFeatures(Map.of(
                "latency", dominantFeature.equals("latency") ? 900.0 : 5.0,
                "error_rate", dominantFeature.equals("error_rate") ? 60.0 : 0.0,
                "cpu", dominantFeature.equals("cpu") ? 85.0 : 5.0,
                "memory", dominantFeature.equals("memory") ? 90.0 : 2.0,
                "request_rate", 0.5
        ));
        state.setLatestPrediction(pred);
        return state;
    }

    @Test
    void testLatencySpikeFavorsTrafficRedirection() {
        // High confidence prior for latency -> TRAFFIC_REDIRECTION
        ServiceAnomalyState state = createAnomalyState("payment-service", "latency", -0.2, 2);
        HealingStrategySelector.RemediationDecision decision = selector.selectRemediation(state);

        assertNotNull(decision);
        assertEquals(HealingActionType.TRAFFIC_REDIRECTION, decision.getActionType());
        assertTrue(decision.getReason().contains("latency") || decision.getReason().contains("SLA"));
    }

    @Test
    void testMemorySaturationFavorsRestart() {
        // High confidence prior for memory -> RESTART
        ServiceAnomalyState state = createAnomalyState("order-service", "memory", -0.3, 2);
        HealingStrategySelector.RemediationDecision decision = selector.selectRemediation(state);

        assertNotNull(decision);
        assertEquals(HealingActionType.RESTART, decision.getActionType());
    }

    @Test
    void testLadderEscalationOnRepeatedFailures() {
        // When consecutive anomalies >= 4, ladder escalates to L3 RESTART
        ServiceAnomalyState state = createAnomalyState("inventory-service", "latency", -0.2, 4);
        HealingStrategySelector.RemediationDecision decision = selector.selectRemediation(state);

        assertNotNull(decision);
        assertEquals(HealingActionType.RESTART, decision.getActionType());
        assertTrue(decision.getReason().contains("Escalation ladder"));
    }

    @Test
    void testOutcomeFeedbackLoopUpdatesPosteriors() {
        String contextKey = "latency:severe";

        // Prior for RATE_LIMIT is (2.0, 2.0)
        Map<String, Map<String, Map<String, Object>>> initialStats = selector.getPolicyStatsSummary();
        Map<String, Object> rateLimitInitial = initialStats.get(contextKey).get("RATE_LIMIT");
        assertEquals(2.0, (Double) rateLimitInitial.get("alpha"), 0.01);
        assertEquals(2.0, (Double) rateLimitInitial.get("beta"), 0.01);

        // Record a failure on RATE_LIMIT
        selector.recordOutcome(contextKey, HealingActionType.RATE_LIMIT, false);

        Map<String, Map<String, Map<String, Object>>> updatedStats = selector.getPolicyStatsSummary();
        Map<String, Object> rateLimitUpdated = updatedStats.get(contextKey).get("RATE_LIMIT");
        assertEquals(2.0, (Double) rateLimitUpdated.get("alpha"), 0.01);
        assertEquals(3.0, (Double) rateLimitUpdated.get("beta"), 0.01); // beta increased by 1!
        assertEquals(1, (Integer) rateLimitUpdated.get("failures"));

        // Record a success on TRAFFIC_REDIRECTION
        selector.recordOutcome(contextKey, HealingActionType.TRAFFIC_REDIRECTION, true);
        Map<String, Object> redirUpdated = selector.getPolicyStatsSummary().get(contextKey).get("TRAFFIC_REDIRECTION");
        assertEquals(6.0, (Double) redirUpdated.get("alpha"), 0.01); // initial 5.0 + 1 = 6.0!
        assertEquals(1, (Integer) redirUpdated.get("successes"));
    }

    @Test
    void testSafetyGuardrailsSuppressRestartWhenCooldownActive() {
        // Mark restart started on order-service
        selector.markRestartStarted("order-service");

        // Try memory fault on order-service (which would normally pick RESTART)
        ServiceAnomalyState state = createAnomalyState("order-service", "memory", -0.3, 2);
        HealingStrategySelector.RemediationDecision decision = selector.selectRemediation(state);

        // RESTART should be suppressed by cooldown -> falls back to TRAFFIC_REDIRECTION or RATE_LIMIT
        assertNotEquals(HealingActionType.RESTART, decision.getActionType());
    }
}
