package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.AnomalyStatus;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

public class SweaConfirmationTest {

    private AnomalyConfirmationService confirmationService;

    @BeforeEach
    void setUp() {
        confirmationService = new AnomalyConfirmationService();
        ReflectionTestUtils.setField(confirmationService, "enabled", true);
        ReflectionTestUtils.setField(confirmationService, "windowSize", 5);
        ReflectionTestUtils.setField(confirmationService, "thresholdCount", 3);
        ReflectionTestUtils.setField(confirmationService, "consecutiveRequired", 3);
        ReflectionTestUtils.setField(confirmationService, "recoveryCycles", 2);
        ReflectionTestUtils.setField(confirmationService, "confirmationMode", "swea");
        ReflectionTestUtils.setField(confirmationService, "sweaA", 1.0);
        ReflectionTestUtils.setField(confirmationService, "sweaB", 2.0);
        ReflectionTestUtils.setField(confirmationService, "sweaLambdaA", 0.9);
        ReflectionTestUtils.setField(confirmationService, "sweaLambdaN", 0.7);
        ReflectionTestUtils.setField(confirmationService, "sweaDelta", 0.5);
        ReflectionTestUtils.setField(confirmationService, "sweaTheta", 3.0);
        ReflectionTestUtils.setField(confirmationService, "sweaSRef", 0.1);
    }

    private MlPredictionResponse createPrediction(String service, boolean isAnomaly, double anomalyScore) {
        MlPredictionResponse res = new MlPredictionResponse();
        res.setService(service);
        res.setIsAnomaly(isAnomaly);
        res.setPrediction(isAnomaly ? "ANOMALY" : "NORMAL");
        res.setAnomalyScore(anomalyScore);
        res.setTimestamp(java.time.Instant.now().toString());
        return res;
    }

    @Test
    void testSevereFaultRapidConfirmation() {
        String service = "payment-service";

        // Severe fault: score = -0.25, ratio = 2.5, gain = 1 + 2*2.5 = 6.0
        // E_1 = 6.0 >= theta (3.0) -> immediately confirmed!
        MlPredictionResponse p1 = createPrediction(service, true, -0.25);
        confirmationService.recordEvaluation(p1);

        ServiceAnomalyState state = confirmationService.getServiceState(service);
        assertEquals(AnomalyStatus.CONFIRMED_ANOMALY, state.getStatus());
        assertTrue(state.getEvidenceScore() >= 3.0, "Evidence should exceed threshold 3.0");
    }

    @Test
    void testMildFaultGradualConfirmation() {
        String service = "order-service";

        // Mild fault: score = -0.05, ratio = 0.5, gain = 1 + 2*0.5 = 2.0
        // E_1 = 2.0 < theta (3.0) -> SUSPECT
        MlPredictionResponse p1 = createPrediction(service, true, -0.05);
        confirmationService.recordEvaluation(p1);

        ServiceAnomalyState state = confirmationService.getServiceState(service);
        assertEquals(AnomalyStatus.SUSPECT, state.getStatus());
        assertEquals(2.0, state.getEvidenceScore(), 0.01);

        // Next evaluation normal -> evidence decays: E_2 = max(0, 0.7 * 2.0 - 0.5) = 0.9
        MlPredictionResponse p2 = createPrediction(service, false, 0.1);
        confirmationService.recordEvaluation(p2);
        assertEquals(0.9, state.getEvidenceScore(), 0.01);
    }

    @Test
    void testEvidenceDecayBackToHealthy() {
        String service = "inventory-service";

        // Step 1: mild anomaly gives E=2.0 -> SUSPECT
        confirmationService.recordEvaluation(createPrediction(service, true, -0.05));
        assertEquals(AnomalyStatus.SUSPECT, confirmationService.getServiceState(service).getStatus());

        // Step 2: normal gives E = 0.9 -> SUSPECT
        confirmationService.recordEvaluation(createPrediction(service, false, 0.1));

        // Step 3: normal gives E = max(0, 0.7*0.9 - 0.5) = 0.13
        confirmationService.recordEvaluation(createPrediction(service, false, 0.1));

        // Step 4: normal gives E = max(0, 0.7*0.13 - 0.5) = 0.0 -> HEALTHY
        confirmationService.recordEvaluation(createPrediction(service, false, 0.1));
        ServiceAnomalyState state = confirmationService.getServiceState(service);
        assertEquals(0.0, state.getEvidenceScore(), 0.01);
        assertEquals(AnomalyStatus.HEALTHY, state.getStatus());
    }

    @Test
    void testFixedModeFallback() {
        ReflectionTestUtils.setField(confirmationService, "confirmationMode", "fixed");
        String service = "payment-service";

        // Single anomaly in fixed mode should enter SUSPECT
        confirmationService.recordEvaluation(createPrediction(service, true, -0.5));
        ServiceAnomalyState state = confirmationService.getServiceState(service);
        assertEquals(AnomalyStatus.SUSPECT, state.getStatus());

        // Second anomaly -> still SUSPECT (consecutive=2 < 3)
        confirmationService.recordEvaluation(createPrediction(service, true, -0.5));
        assertEquals(AnomalyStatus.SUSPECT, state.getStatus());

        // Third anomaly -> consecutive=3 -> CONFIRMED
        confirmationService.recordEvaluation(createPrediction(service, true, -0.5));
        assertEquals(AnomalyStatus.CONFIRMED_ANOMALY, state.getStatus());
    }
}
