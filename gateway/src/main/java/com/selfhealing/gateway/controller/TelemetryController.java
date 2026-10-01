package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.ServiceTelemetry;
import com.selfhealing.gateway.service.TelemetryCollectorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway/telemetry")
public class TelemetryController {

    private final TelemetryCollectorService telemetryService;

    public TelemetryController(TelemetryCollectorService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping("/latest")
    public Mono<Map<String, ServiceTelemetry>> getLatest() {
        return Mono.just(telemetryService.getLatestTelemetry());
    }

    @GetMapping("/history")
    public Mono<List<ServiceTelemetry>> getHistory() {
        return Mono.just(telemetryService.getHistory());
    }

    @PostMapping("/collect")
    public Mono<List<ServiceTelemetry>> triggerCollection() {
        return Mono.fromCallable(telemetryService::collectAll);
    }
}
