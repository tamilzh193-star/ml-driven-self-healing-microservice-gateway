package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.ServiceTelemetry;
import com.selfhealing.gateway.service.MlClientService;
import com.selfhealing.gateway.service.TelemetryCollectorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway/ml")
public class MlController {

    private final MlClientService mlClientService;
    private final TelemetryCollectorService telemetryCollectorService;

    public MlController(MlClientService mlClientService, TelemetryCollectorService telemetryCollectorService) {
        this.mlClientService = mlClientService;
        this.telemetryCollectorService = telemetryCollectorService;
    }

    /**
     * Check status and connectivity to the Python Flask ML Service.
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getMlStatus() {
        return Mono.fromCallable(() -> ResponseEntity.ok(mlClientService.checkMlHealth()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Get the latest anomaly prediction for all monitored microservices.
     */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, MlPredictionResponse>> getLatestPredictions() {
        return ResponseEntity.ok(mlClientService.getLatestPredictions());
    }

    /**
     * Get rolling history of recent ML predictions.
     */
    @GetMapping("/history")
    public ResponseEntity<List<MlPredictionResponse>> getPredictionHistory() {
        return ResponseEntity.ok(mlClientService.getHistory());
    }

    /**
     * Trigger immediate telemetry collection and return fresh ML predictions.
     */
    @PostMapping("/evaluate")
    public Mono<ResponseEntity<List<MlPredictionResponse>>> triggerEvaluation() {
        return Mono.fromCallable(() -> {
            List<ServiceTelemetry> telemetry = telemetryCollectorService.collectAll();
            List<MlPredictionResponse> predictions = mlClientService.evaluateBatch(telemetry);
            return ResponseEntity.ok(predictions);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
