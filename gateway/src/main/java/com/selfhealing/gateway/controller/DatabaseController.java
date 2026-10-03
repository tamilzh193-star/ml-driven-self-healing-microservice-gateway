package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.service.DatabasePersistenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway/db")
public class DatabaseController {

    private final DatabasePersistenceService databasePersistenceService;

    public DatabaseController(DatabasePersistenceService databasePersistenceService) {
        this.databasePersistenceService = databasePersistenceService;
    }

    /**
     * Inspect database connectivity, product metadata, and table row counts.
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getDatabaseStatus() {
        return Mono.fromCallable(() -> ResponseEntity.ok(databasePersistenceService.getDatabaseStatus()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Retrieve recent telemetry records persisted in the database.
     */
    @GetMapping("/telemetry")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getTelemetry(
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> ResponseEntity.ok(databasePersistenceService.getRecentTelemetry(service, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Retrieve recent healing audit logs persisted in the database.
     */
    @GetMapping("/healing")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getHealingAudits(
            @RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> ResponseEntity.ok(databasePersistenceService.getRecentHealingAudits(limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Retrieve recent confirmed anomaly records persisted in the database.
     */
    @GetMapping("/anomalies")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getAnomalies(
            @RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> ResponseEntity.ok(databasePersistenceService.getRecentAnomalies(limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Administrative reset of database tables.
     */
    @PostMapping("/reset")
    public Mono<ResponseEntity<Map<String, String>>> resetDatabase() {
        return Mono.fromCallable(() -> {
            databasePersistenceService.resetAllTables();
            return ResponseEntity.ok(Collections.singletonMap("message", "All database tables and persistence buffers reset successfully"));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
