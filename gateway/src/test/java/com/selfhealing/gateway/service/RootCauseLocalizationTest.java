package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.AnomalyStatus;
import com.selfhealing.gateway.model.HealingAuditRecord;
import com.selfhealing.gateway.model.HealingStatus;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Novelty N4 Verification: Topology-Aware Root-Cause Localization.
 * Tests dependency suppression to prevent redundant/disruptive remediations
 * on upstream caller services (order-service) when degradation originates
 * in downstream dependencies (payment-service / inventory-service).
 */
public class RootCauseLocalizationTest {

    private ServiceTopologyService topologyService;
    private HealingEngineService healingEngineService;
    private HealingStrategySelector strategySelector;
    private RemediationExecutionService remediationExecutionService;
    private AnomalyConfirmationService anomalyConfirmationService;

    @BeforeEach
    void setUp() {
        topologyService = new ServiceTopologyService();
        strategySelector = mock(HealingStrategySelector.class);
        remediationExecutionService = mock(RemediationExecutionService.class);
        anomalyConfirmationService = mock(AnomalyConfirmationService.class);

        healingEngineService = new HealingEngineService(strategySelector, remediationExecutionService);
        healingEngineService.setTopologyService(topologyService);
        healingEngineService.setAnomalyConfirmationService(anomalyConfirmationService);
    }

    @Test
    void testDownstreamPaymentAnomalySuppressesOrderServiceLatencyRemediation() {
        // Given downstream payment-service has a confirmed anomaly
        ServiceAnomalyState paymentState = new ServiceAnomalyState("payment-service", 5);
        paymentState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        paymentState.setDominantFeature("latency");
        paymentState.setFailureSignature("LATENCY_SPIKE");

        // And upstream order-service has a latency symptom
        ServiceAnomalyState orderState = new ServiceAnomalyState("order-service", 5);
        orderState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        orderState.setDominantFeature("latency");
        orderState.setFailureSignature("LATENCY_SPIKE");

        Map<String, ServiceAnomalyState> activeStates = new HashMap<>();
        activeStates.put("payment-service", paymentState);
        activeStates.put("order-service", orderState);

        when(anomalyConfirmationService.getAllStates()).thenReturn(activeStates);

        // When healService is invoked on order-service
        HealingAuditRecord record = healingEngineService.healService(orderState, false);

        // Then remediation should be SUPPRESSED with SUPPRESSED_TOPOLOGY status
        assertNotNull(record);
        assertEquals(HealingStatus.SUPPRESSED_TOPOLOGY, record.getActionStatus());
        assertEquals("SUPPRESSED", record.getRecoveryStatus());
        assertTrue(record.getTriggerReason().contains("payment-service"));

        // Verify that NO actual remediation execution took place on order-service
        verify(remediationExecutionService, never()).executeRemediation(anyString(), any());
    }

    @Test
    void testOrderInternalMemoryLeakIsNotSuppressedEvenIfDownstreamAnomalous() {
        // Downstream payment-service is anomalous
        ServiceAnomalyState paymentState = new ServiceAnomalyState("payment-service", 5);
        paymentState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        paymentState.setDominantFeature("latency");
        paymentState.setFailureSignature("LATENCY_SPIKE");

        // BUT order-service has an internal MEMORY_LEAK (not a downstream network symptom!)
        ServiceAnomalyState orderState = new ServiceAnomalyState("order-service", 5);
        orderState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        orderState.setDominantFeature("memory");
        orderState.setFailureSignature("MEMORY_LEAK");

        Map<String, ServiceAnomalyState> activeStates = new HashMap<>();
        activeStates.put("payment-service", paymentState);
        activeStates.put("order-service", orderState);

        when(anomalyConfirmationService.getAllStates()).thenReturn(activeStates);

        Optional<String> rootCause = topologyService.findDownstreamRootCause("order-service", orderState, activeStates);

        // Should NOT identify downstream as root cause because MEMORY_LEAK is internal
        assertFalse(rootCause.isPresent());
    }

    @Test
    void testNoDownstreamAnomalyAllowsOrderServiceRemediation() {
        // Downstream payment-service is HEALTHY
        ServiceAnomalyState paymentState = new ServiceAnomalyState("payment-service", 5);
        paymentState.setStatus(AnomalyStatus.HEALTHY);

        ServiceAnomalyState orderState = new ServiceAnomalyState("order-service", 5);
        orderState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        orderState.setDominantFeature("latency");
        orderState.setFailureSignature("LATENCY_SPIKE");

        Map<String, ServiceAnomalyState> activeStates = new HashMap<>();
        activeStates.put("payment-service", paymentState);
        activeStates.put("order-service", orderState);

        when(anomalyConfirmationService.getAllStates()).thenReturn(activeStates);

        Optional<String> rootCause = topologyService.findDownstreamRootCause("order-service", orderState, activeStates);

        // No downstream anomaly -> no suppression
        assertFalse(rootCause.isPresent());
    }

    @Test
    void testInventoryDownstreamAnomalyCorrectlyIdentified() {
        // Downstream inventory-service has a confirmed anomaly
        ServiceAnomalyState invState = new ServiceAnomalyState("inventory-service", 5);
        invState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        invState.setDominantFeature("error_rate");
        invState.setFailureSignature("ERROR_BURST");

        ServiceAnomalyState orderState = new ServiceAnomalyState("order-service", 5);
        orderState.setStatus(AnomalyStatus.CONFIRMED_ANOMALY);
        orderState.setDominantFeature("error_rate");
        orderState.setFailureSignature("ERROR_BURST");

        Map<String, ServiceAnomalyState> activeStates = new HashMap<>();
        activeStates.put("inventory-service", invState);
        activeStates.put("order-service", orderState);

        when(anomalyConfirmationService.getAllStates()).thenReturn(activeStates);

        Optional<String> rootCause = topologyService.findDownstreamRootCause("order-service", orderState, activeStates);

        assertTrue(rootCause.isPresent());
        assertEquals("inventory-service", rootCause.get());
    }
}
