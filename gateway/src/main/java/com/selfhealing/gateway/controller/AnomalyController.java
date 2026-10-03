package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.ServiceAnomalyState;
import com.selfhealing.gateway.service.AnomalyConfirmationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway/anomaly")
public class AnomalyController {

    private final AnomalyConfirmationService anomalyConfirmationService;

    public AnomalyController(AnomalyConfirmationService anomalyConfirmationService) {
        this.anomalyConfirmationService = anomalyConfirmationService;
    }

    /**
     * Get anomaly confirmation states for all monitored microservices.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, ServiceAnomalyState>> getAllStates() {
        return ResponseEntity.ok(anomalyConfirmationService.getAllStates());
    }

    /**
     * Get anomaly confirmation state for a specific microservice.
     */
    @GetMapping("/status/{serviceName}")
    public ResponseEntity<ServiceAnomalyState> getServiceState(@PathVariable String serviceName) {
        return ResponseEntity.ok(anomalyConfirmationService.getServiceState(serviceName));
    }

    /**
     * Get all services currently evaluated as CONFIRMED_ANOMALY.
     * This endpoint feeds the autonomous recovery engine in Phase 12.
     */
    @GetMapping("/confirmed")
    public ResponseEntity<List<ServiceAnomalyState>> getConfirmedAnomalies() {
        return ResponseEntity.ok(anomalyConfirmationService.getConfirmedAnomalies());
    }

    /**
     * Reset confirmation states for all microservices.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetAll() {
        anomalyConfirmationService.resetAll();
        return ResponseEntity.ok(Collections.singletonMap("message", "All anomaly confirmation states reset successfully"));
    }

    /**
     * Reset confirmation state for a specific microservice.
     */
    @PostMapping("/reset/{serviceName}")
    public ResponseEntity<Map<String, String>> resetService(@PathVariable String serviceName) {
        anomalyConfirmationService.resetState(serviceName);
        return ResponseEntity.ok(Collections.singletonMap("message", "Anomaly confirmation state for " + serviceName + " reset successfully"));
    }
}
