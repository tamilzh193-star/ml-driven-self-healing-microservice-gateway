package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.HealingAuditRecord;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import com.selfhealing.gateway.service.AnomalyConfirmationService;
import com.selfhealing.gateway.service.HealingEngineService;
import com.selfhealing.gateway.service.RemediationExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/gateway/healing")
public class HealingController {

    private final HealingEngineService healingEngineService;
    private final AnomalyConfirmationService anomalyConfirmationService;
    private final RemediationExecutionService remediationExecutionService;

    public HealingController(HealingEngineService healingEngineService,
                             AnomalyConfirmationService anomalyConfirmationService,
                             RemediationExecutionService remediationExecutionService) {
        this.healingEngineService = healingEngineService;
        this.anomalyConfirmationService = anomalyConfirmationService;
        this.remediationExecutionService = remediationExecutionService;
    }

    /**
     * Inspect current status of the Healing Engine:
     * active traffic redirections, active rate limits, active cooldown timers, and retry attempts.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getHealingStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeRedirections", remediationExecutionService.getActiveRedirections());
        status.put("activeRateLimits", remediationExecutionService.getActiveRateLimits());

        Map<String, String> cooldownsFormatted = new LinkedHashMap<>();
        Instant now = Instant.now();
        healingEngineService.getCooldowns().forEach((svc, instant) -> {
            if (instant != null && now.isBefore(instant)) {
                cooldownsFormatted.put(svc, instant.toString());
            }
        });
        status.put("activeCooldowns", cooldownsFormatted);
        status.put("retryCounts", healingEngineService.getRetryCounts());
        status.put("totalAuditRecords", healingEngineService.getAuditHistory().size());

        return ResponseEntity.ok(status);
    }

    /**
     * Retrieve the immutable audit trail of all autonomous self-healing remediation actions.
     */
    @GetMapping("/history")
    public ResponseEntity<List<HealingAuditRecord>> getHealingHistory() {
        return ResponseEntity.ok(healingEngineService.getAuditHistory());
    }

    /**
     * Manually trigger closed-loop healing on a specific service (for testing / admin operations).
     */
    @PostMapping("/trigger/{serviceName}")
    public Mono<ResponseEntity<HealingAuditRecord>> triggerHealing(@PathVariable String serviceName,
                                                                   @RequestParam(defaultValue = "true") boolean force) {
        return Mono.fromCallable(() -> {
            ServiceAnomalyState state = anomalyConfirmationService.getServiceState(serviceName);
            HealingAuditRecord record = healingEngineService.healService(state, force);
            if (record == null) {
                HealingAuditRecord rejected = new HealingAuditRecord(
                        serviceName, null, "Remediation rejected: service not in CONFIRMED_ANOMALY state",
                        state != null ? state.getAverageAnomalyScore() : 0.0, 0
                );
                rejected.setActionStatus(com.selfhealing.gateway.model.HealingStatus.INITIATED);
                rejected.setRecoveryStatus("REJECTED_NOT_CONFIRMED");
                return ResponseEntity.ok(rejected);
            }
            return ResponseEntity.ok(record);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Administrative reset of all healing engine states, active remediations, cooldowns, and audit history.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetHealingEngine() {
        healingEngineService.resetEngine();
        return ResponseEntity.ok(Collections.singletonMap("message", "Healing engine states, active filters, and audit logs reset successfully"));
    }
}
