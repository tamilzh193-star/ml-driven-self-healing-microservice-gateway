package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.HealingActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying Policy Statistics Persistence and Startup Restoration (Checkpoint Fix).
 */
public class PolicyPersistenceTest {

    private DatabasePersistenceService databasePersistenceService;
    private JdbcTemplate jdbcTemplate;
    private HealingStrategySelector strategySelector;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        databasePersistenceService = new DatabasePersistenceService(jdbcTemplate, null);
        strategySelector = new HealingStrategySelector();
    }

    @Test
    @DisplayName("Verify that valid persisted policy statistics are restored into the active policy map at startup")
    void testPolicyRestorationSuccess() {
        // Arrange: mock persisted database rows with learned posteriors
        List<Map<String, Object>> mockRows = new ArrayList<>();
        Map<String, Object> row1 = new HashMap<>();
        row1.put("context_key", "latency:severe");
        row1.put("action_type", "TRAFFIC_REDIRECTION");
        row1.put("alpha", 12.0);
        row1.put("beta", 3.0);
        row1.put("successes", 10);
        row1.put("failures", 2);
        mockRows.add(row1);

        Map<String, Object> row2 = new HashMap<>();
        row2.put("context_key", "memory:severe");
        row2.put("action_type", "RESTART");
        row2.put("alpha", 15.0);
        row2.put("beta", 1.0);
        row2.put("successes", 14);
        row2.put("failures", 0);
        mockRows.add(row2);

        when(jdbcTemplate.queryForList(anyString())).thenReturn(mockRows);

        // Act: attach persistence service and trigger startup restoration
        strategySelector.setDatabasePersistenceService(databasePersistenceService);
        strategySelector.loadPersistedPoliciesFromDatabase();

        // Assert: inspect restored policy stats
        Map<String, Map<String, Map<String, Object>>> summary = strategySelector.getPolicyStatsSummary();
        assertNotNull(summary);

        // Check latency:severe -> TRAFFIC_REDIRECTION
        Map<String, Object> latencyRedir = summary.get("latency:severe").get("TRAFFIC_REDIRECTION");
        assertNotNull(latencyRedir);
        assertEquals(12.0, (Double) latencyRedir.get("alpha"), 0.001);
        assertEquals(3.0, (Double) latencyRedir.get("beta"), 0.001);
        assertEquals(10, latencyRedir.get("successes"));
        assertEquals(2, latencyRedir.get("failures"));

        // Check memory:severe -> RESTART
        Map<String, Object> memRestart = summary.get("memory:severe").get("RESTART");
        assertNotNull(memRestart);
        assertEquals(15.0, (Double) memRestart.get("alpha"), 0.001);
        assertEquals(1.0, (Double) memRestart.get("beta"), 0.001);
        assertEquals(14, memRestart.get("successes"));
        assertEquals(0, memRestart.get("failures"));

        // Confirm missing rows retain default domain priors
        Map<String, Object> latencyRestart = summary.get("latency:severe").get("RESTART");
        assertNotNull(latencyRestart);
        assertEquals(1.0, (Double) latencyRestart.get("alpha"), 0.001);
        assertEquals(3.0, (Double) latencyRestart.get("beta"), 0.001);
    }

    @Test
    @DisplayName("Verify that invalid records are rejected and skipped without crashing or corrupting priors")
    void testPolicyRestorationSkipsInvalidRecords() {
        List<Map<String, Object>> corruptRows = new ArrayList<>();

        // Invalid record 1: unknown action type
        Map<String, Object> badAction = new HashMap<>();
        badAction.put("context_key", "latency:severe");
        badAction.put("action_type", "INVALID_ACTION");
        badAction.put("alpha", 10.0);
        badAction.put("beta", 2.0);
        badAction.put("successes", 5);
        badAction.put("failures", 1);
        corruptRows.add(badAction);

        // Invalid record 2: negative alpha
        Map<String, Object> negAlpha = new HashMap<>();
        negAlpha.put("context_key", "cpu:mild");
        negAlpha.put("action_type", "RATE_LIMIT");
        negAlpha.put("alpha", -2.0);
        negAlpha.put("beta", 3.0);
        negAlpha.put("successes", 0);
        negAlpha.put("failures", 0);
        corruptRows.add(negAlpha);

        // Invalid record 3: NaN beta
        Map<String, Object> nanBeta = new HashMap<>();
        nanBeta.put("context_key", "cpu:mild");
        nanBeta.put("action_type", "RATE_LIMIT");
        nanBeta.put("alpha", 4.0);
        nanBeta.put("beta", Double.NaN);
        nanBeta.put("successes", 0);
        nanBeta.put("failures", 0);
        corruptRows.add(nanBeta);

        // Invalid record 4: negative count
        Map<String, Object> negCount = new HashMap<>();
        negCount.put("context_key", "cpu:mild");
        negCount.put("action_type", "RATE_LIMIT");
        negCount.put("alpha", 4.0);
        negCount.put("beta", 2.0);
        negCount.put("successes", -5);
        negCount.put("failures", 0);
        corruptRows.add(negCount);

        when(jdbcTemplate.queryForList(anyString())).thenReturn(corruptRows);

        strategySelector.setDatabasePersistenceService(databasePersistenceService);
        assertDoesNotThrow(() -> strategySelector.loadPersistedPoliciesFromDatabase());

        // Assert: cpu:mild RATE_LIMIT retains original default prior (5.0, 1.0)
        Map<String, Map<String, Map<String, Object>>> summary = strategySelector.getPolicyStatsSummary();
        Map<String, Object> cpuRateLimit = summary.get("cpu:mild").get("RATE_LIMIT");
        assertEquals(5.0, (Double) cpuRateLimit.get("alpha"), 0.001);
        assertEquals(1.0, (Double) cpuRateLimit.get("beta"), 0.001);
        assertEquals(0, cpuRateLimit.get("successes"));
    }

    @Test
    @DisplayName("Verify that database unavailability safely falls back to default priors without error")
    void testPolicyRestorationDatabaseOfflineFallback() {
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new RuntimeException("Connection refused: MySQL offline"));

        strategySelector.setDatabasePersistenceService(databasePersistenceService);
        assertDoesNotThrow(() -> strategySelector.loadPersistedPoliciesFromDatabase());

        // Default priors remain completely intact
        Map<String, Map<String, Map<String, Object>>> summary = strategySelector.getPolicyStatsSummary();
        assertNotNull(summary);
        assertTrue(summary.containsKey("latency:severe"));
        assertEquals(2.0, (Double) summary.get("latency:severe").get("RATE_LIMIT").get("alpha"), 0.001);
    }

    @Test
    @DisplayName("Verify that when persistence is disabled or null, defaults are retained safely")
    void testPolicyRestorationWhenPersistenceServiceNull() {
        strategySelector.setDatabasePersistenceService(null);
        assertDoesNotThrow(() -> strategySelector.loadPersistedPoliciesFromDatabase());

        Map<String, Map<String, Map<String, Object>>> summary = strategySelector.getPolicyStatsSummary();
        assertNotNull(summary);
        assertEquals(5.0, (Double) summary.get("cpu:mild").get("RATE_LIMIT").get("alpha"), 0.001);
    }
}
