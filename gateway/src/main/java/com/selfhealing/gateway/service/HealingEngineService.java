package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.gateway.model.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Autonomous Healing Engine coordinating closed-loop self-healing:
 * 1. Consumes confirmed anomalies from AnomalyConfirmationService (Phase 11).
 * 2. Selects targeted remediation strategy via HealingStrategySelector (Rule-based).
 * 3. Enforces anti-thrashing cooldown and maximum retry protections.
 * 4. Executes remediation (RESTART, TRAFFIC_REDIRECTION, RATE_LIMIT) via RemediationExecutionService.
 * 5. Verifies post-healing recovery via service health + telemetry probes.
 * 6. Logs immutable audit records for monitoring and Phase 13 MySQL persistence.
 */
@Service
public class HealingEngineService {

    private static final Logger log = LoggerFactory.getLogger(HealingEngineService.class);
    private static final int MAX_AUDIT_HISTORY = 200;

    @Value("${healing.engine.enabled:true}")
    private boolean enabled;

    @Value("${healing.engine.cooldown-seconds:30}")
    private int cooldownSeconds;

    @Value("${healing.engine.max-retries:3}")
    private int maxRetries;

    @Value("${healing.engine.verification-delay-ms:2500}")
    private long verificationDelayMs;

    private final HealingStrategySelector strategySelector;
    private final RemediationExecutionService remediationExecutionService;
    private final WebClient webClient;

    @Autowired
    @Lazy
    private AnomalyConfirmationService anomalyConfirmationService;

    @Autowired(required = false)
    private DatabasePersistenceService databasePersistenceService;

    // Cooldown map: serviceName -> Instant until which healing is suppressed
    private final Map<String, Instant> cooldownUntilMap = new ConcurrentHashMap<>();

    // Retry counter: serviceName -> count of consecutive healing attempts
    private final Map<String, AtomicInteger> retryCountMap = new ConcurrentHashMap<>();

    // Healing audit log history
    private final Deque<HealingAuditRecord> auditHistory = new ConcurrentLinkedDeque<>();

    private final Map<String, String> serviceUrls = new ConcurrentHashMap<>();
    private final Map<String, String> businessPaths = new ConcurrentHashMap<>();

    public HealingEngineService(HealingStrategySelector strategySelector,
                                RemediationExecutionService remediationExecutionService) {
        this.strategySelector = strategySelector;
        this.remediationExecutionService = remediationExecutionService;
        this.webClient = WebClient.builder().build();

        serviceUrls.put("order-service", "http://localhost:8081");
        serviceUrls.put("payment-service", "http://localhost:8082");
        serviceUrls.put("inventory-service", "http://localhost:8083");

        businessPaths.put("order-service", "/orders");
        businessPaths.put("payment-service", "/payments");
        businessPaths.put("inventory-service", "/inventory");
    }

    /**
     * Periodic reconciliation evaluating confirmed anomalies from Phase 11.
     */
    @Scheduled(fixedRate = 5000)
    public void evaluateConfirmedAnomalies() {
        if (!enabled || anomalyConfirmationService == null) {
            return;
        }

        List<ServiceAnomalyState> confirmedList = anomalyConfirmationService.getConfirmedAnomalies();
        if (!confirmedList.isEmpty()) {
            for (ServiceAnomalyState state : confirmedList) {
                healService(state, false);
            }
        }
    }

    /**
     * Executes closed-loop self-healing on a confirmed anomaly.
     */
    public HealingAuditRecord healService(ServiceAnomalyState state, boolean forceManual) {
        String serviceName = state.getServiceName();
        Double anomalyScore = state.getAverageAnomalyScore();

        Instant now = Instant.now();
        Instant cooldownUntil = cooldownUntilMap.get(serviceName);

        // Rule 4: Cooldown Protection
        if (!forceManual && cooldownUntil != null && now.isBefore(cooldownUntil)) {
            log.warn("[HEALING COOLDOWN] Remediation for '{}' suppressed. Active cooldown until {}", serviceName, cooldownUntil);
            HealingAuditRecord record = new HealingAuditRecord(
                    serviceName, null, "Remediation suppressed by active anti-thrashing cooldown",
                    anomalyScore, retryCountMap.computeIfAbsent(serviceName, k -> new AtomicInteger(0)).get()
            );
            record.setActionStatus(HealingStatus.SKIPPED_COOLDOWN);
            record.setRecoveryStatus("SKIPPED");
            record.setRecoveryDetails("Service in cooldown until " + cooldownUntil);
            record.setCooldownUntil(cooldownUntil.toString());
            recordAudit(record);
            return record;
        }

        // Rule 1: Must consume ONLY confirmed anomalies (unless explicitly forced by admin API)
        if (!forceManual && state.getStatus() != AnomalyStatus.CONFIRMED_ANOMALY) {
            log.warn("[HEALING REJECTED] Service '{}' is in {} state (not CONFIRMED_ANOMALY). Raw anomaly cannot trigger healing.",
                    serviceName, state.getStatus());
            return null;
        }

        AtomicInteger retries = retryCountMap.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int currentAttempt = retries.incrementAndGet();

        // Rule 5: Maximum Retry Protection
        if (!forceManual && currentAttempt > maxRetries) {
            log.error("[HEALING MAX RETRIES EXCEEDED] Service '{}' exceeded max retries ({}/{}). Escalating to CRITICAL.",
                    serviceName, currentAttempt, maxRetries);
            HealingAuditRecord record = new HealingAuditRecord(
                    serviceName, null, String.format("Exceeded max retry attempts (%d/%d) without recovery", currentAttempt, maxRetries),
                    anomalyScore, currentAttempt
            );
            record.setActionStatus(HealingStatus.MAX_RETRIES_EXCEEDED);
            record.setRecoveryStatus("FAILED");
            record.setRecoveryDetails("Service flagged UNRECOVERABLE / CRITICAL. Manual admin intervention required.");
            recordAudit(record);
            return record;
        }

        // Rule 2 & 3: Select Remediation Strategy
        HealingStrategySelector.RemediationDecision decision = strategySelector.selectRemediation(state);
        HealingActionType actionType = decision.getActionType();
        String reason = decision.getReason();

        HealingAuditRecord record = new HealingAuditRecord(serviceName, actionType, reason, anomalyScore, currentAttempt);
        record.setActionStatus(HealingStatus.EXECUTING);

        long start = System.currentTimeMillis();
        boolean executed = remediationExecutionService.executeRemediation(serviceName, actionType);
        long execDuration = System.currentTimeMillis() - start;
        record.setExecutionDurationMs(execDuration);

        // Set cooldown timer
        Instant nextCooldown = Instant.now().plusSeconds(cooldownSeconds);
        cooldownUntilMap.put(serviceName, nextCooldown);
        record.setCooldownUntil(nextCooldown.toString());

        if (!executed) {
            record.setActionStatus(HealingStatus.FAILED_RECOVERY);
            record.setRecoveryStatus("FAILED");
            record.setRecoveryDetails("Execution of " + actionType + " returned false or failed with error");
            recordAudit(record);
            return record;
        }

        // Rule 6: Post-Remediation Recovery Verification
        log.info("[HEALING VERIFY] Waiting {}ms before verifying recovery for service '{}'...", verificationDelayMs, serviceName);
        try {
            Thread.sleep(verificationDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean verified = verifyRecovery(serviceName);

        if (verified) {
            record.setActionStatus(HealingStatus.RECOVERED);
            record.setRecoveryStatus("RECOVERED");
            record.setRecoveryDetails(String.format("Service '%s' verified healthy: /actuator/health is UP and probe responded within SLA", serviceName));
            retries.set(0); // Reset retry counter upon successful recovery

            // Restore normal routing & rate limiting if temporary mitigations were active
            remediationExecutionService.deactivateRemediations(serviceName);

            // Notify confirmation service to clear state or mark healthy
            if (anomalyConfirmationService != null) {
                anomalyConfirmationService.resetState(serviceName);
            }
            log.info("[HEALING RECOVERY CONFIRMED] Service '{}' successfully recovered after {}! State reset to HEALTHY.",
                    serviceName, actionType);
        } else {
            record.setActionStatus(HealingStatus.FAILED_RECOVERY);
            record.setRecoveryStatus("FAILED");
            record.setRecoveryDetails(String.format("Service '%s' failed post-healing verification (attempt %d/%d)", serviceName, currentAttempt, maxRetries));
            log.warn("[HEALING VERIFICATION FAILED] Service '{}' failed post-healing verification on attempt {}/{}",
                    serviceName, currentAttempt, maxRetries);
        }

        recordAudit(record);
        return record;
    }

    /**
     * Probes the service's actuator health and business endpoint to verify real recovery.
     */
    public boolean verifyRecovery(String serviceName) {
        String baseUrl = serviceUrls.get(serviceName);
        String businessPath = businessPaths.getOrDefault(serviceName, "/");

        if (baseUrl == null) {
            return false;
        }

        try {
            // 1. Health check
            JsonNode healthNode = webClient.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(3000));

            if (healthNode == null || !"UP".equalsIgnoreCase(healthNode.path("status").asText())) {
                log.warn("[VERIFY] {} /actuator/health is not UP", serviceName);
                return false;
            }

            // 2. Business probe to ensure latency is normal and not throwing errors
            long probeStart = System.currentTimeMillis();
            webClient.get()
                    .uri(baseUrl + businessPath)
                    .retrieve()
                    .toBodilessEntity()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(3000));

            long duration = System.currentTimeMillis() - probeStart;
            log.info("[VERIFY] {} business probe latency: {}ms", serviceName, duration);

            return duration < 1000;

        } catch (Exception e) {
            log.warn("[VERIFY] Error probing {} during recovery verification: {}", serviceName, e.getMessage());
            return false;
        }
    }

    private void recordAudit(HealingAuditRecord record) {
        auditHistory.addFirst(record);
        while (auditHistory.size() > MAX_AUDIT_HISTORY) {
            auditHistory.pollLast();
        }
        log.info("[HEALING AUDIT] {}", record);

        if (databasePersistenceService != null) {
            try {
                databasePersistenceService.persistHealingAuditAsync(record).subscribe();
            } catch (Exception e) {
                log.debug("Database async audit persistence error: {}", e.getMessage());
            }
        }
    }

    public List<HealingAuditRecord> getAuditHistory() {
        return new ArrayList<>(auditHistory);
    }

    public Map<String, Instant> getCooldowns() {
        return new HashMap<>(cooldownUntilMap);
    }

    public Map<String, Integer> getRetryCounts() {
        Map<String, Integer> counts = new HashMap<>();
        retryCountMap.forEach((k, v) -> counts.put(k, v.get()));
        return counts;
    }

    public synchronized void resetEngine() {
        cooldownUntilMap.clear();
        retryCountMap.clear();
        auditHistory.clear();
        remediationExecutionService.resetAll();
        log.info("Reset Healing Engine states (cooldowns, retries, audit history).");
    }
}
