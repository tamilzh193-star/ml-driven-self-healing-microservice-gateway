package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.result.view.Rendering;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DashboardControllerTest {

    private DashboardController controller;
    private TelemetryCollectorService telemetryService;
    private AnomalyConfirmationService anomalyService;
    private HealingEngineService healingService;
    private RemediationExecutionService remediationService;
    private MlClientService mlClientService;

    @BeforeEach
    void setUp() {
        telemetryService = mock(TelemetryCollectorService.class);
        anomalyService = mock(AnomalyConfirmationService.class);
        healingService = mock(HealingEngineService.class);
        remediationService = mock(RemediationExecutionService.class);
        mlClientService = mock(MlClientService.class);

        when(telemetryService.getLatestTelemetry()).thenReturn(Collections.emptyMap());
        when(anomalyService.getAllStates()).thenReturn(Collections.emptyMap());
        when(healingService.getAuditHistory()).thenReturn(Collections.emptyList());
        when(healingService.getCooldowns()).thenReturn(Collections.emptyMap());
        when(healingService.getRetryCounts()).thenReturn(Collections.emptyMap());
        when(remediationService.getActiveRedirections()).thenReturn(Collections.emptySet());
        when(remediationService.getActiveRateLimits()).thenReturn(Collections.emptyMap());
        when(mlClientService.checkMlHealth()).thenReturn(Map.of("status", "UP"));

        controller = new DashboardController(
                telemetryService,
                anomalyService,
                healingService,
                remediationService,
                mlClientService
        );
    }

    @Test
    @DisplayName("Verify that renderDashboard returns the dashboard view and data model")
    void testRenderDashboard() {
        Rendering rendering = controller.renderDashboard().block();
        assertNotNull(rendering);
        assertEquals("dashboard", rendering.view());
        assertTrue(rendering.modelAttributes().containsKey("data"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) rendering.modelAttributes().get("data");
        assertNotNull(data);
        assertTrue(data.containsKey("services"));
        assertTrue(data.containsKey("infrastructure"));
        assertTrue(data.containsKey("healingEngine"));
        assertTrue(data.containsKey("mlService"));
    }

    @Test
    @DisplayName("Verify that renderProjectInfo returns the project-info view and data model")
    void testRenderProjectInfo() {
        Rendering rendering = controller.renderProjectInfo().block();
        assertNotNull(rendering);
        assertEquals("project-info", rendering.view());
        assertTrue(rendering.modelAttributes().containsKey("data"));
    }

    @Test
    @DisplayName("Verify that getDashboardStats returns the complete aggregated live stats payload")
    void testGetDashboardStats() {
        Map<String, Object> stats = controller.getDashboardStats().block();
        assertNotNull(stats);
        assertTrue(stats.containsKey("timestamp"));
        assertTrue(stats.containsKey("services"));
        assertTrue(stats.containsKey("kpis"));
        assertTrue(stats.containsKey("mlService"));

        @SuppressWarnings("unchecked")
        Map<String, Object> mlInfo = (Map<String, Object>) stats.get("mlService");
        assertNotNull(mlInfo);
        assertEquals(0.04, mlInfo.get("contamination"));
        assertEquals(-0.5140227742629923, (Double) mlInfo.get("decisionThreshold"), 0.000001);
        assertEquals(0.9448, (Double) mlInfo.get("heldOutAccuracy"), 0.0001);
        assertEquals(308, mlInfo.get("heldOutSamples"));
    }
}
