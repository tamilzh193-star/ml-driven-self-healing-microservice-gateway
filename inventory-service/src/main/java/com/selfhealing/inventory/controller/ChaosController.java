package com.selfhealing.inventory.controller;

import com.selfhealing.inventory.chaos.ChaosState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private final ChaosState chaosState;

    public ChaosController(ChaosState chaosState) {
        this.chaosState = chaosState;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/slow")
    public ResponseEntity<Map<String, Object>> injectSlow(@RequestParam(defaultValue = "1500") int delayMs) {
        chaosState.setSlow(delayMs);
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/error")
    public ResponseEntity<Map<String, Object>> injectError(@RequestParam(defaultValue = "true") boolean active) {
        chaosState.setError(active);
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/cpu")
    public ResponseEntity<Map<String, Object>> injectCpu(@RequestParam(defaultValue = "30") int durationSec) {
        chaosState.triggerCpuStress(durationSec);
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/memory")
    public ResponseEntity<Map<String, Object>> injectMemory(@RequestParam(defaultValue = "50") int megabytes) {
        chaosState.allocateMemory(megabytes);
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/leak")
    public ResponseEntity<Map<String, Object>> injectPermanentLeak(@RequestParam(defaultValue = "50") int megabytes) {
        chaosState.leakMemory(megabytes);
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/crash")
    public ResponseEntity<Map<String, Object>> crashService() {
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
            Runtime.getRuntime().halt(1);
        }, "chaos-crash-thread").start();
        return ResponseEntity.ok(Map.of("status", "CRASHING", "message", "Service JVM halting in 200ms. True restart required."));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetChaos() {
        chaosState.reset();
        return ResponseEntity.ok(chaosState.getStatus());
    }
}
