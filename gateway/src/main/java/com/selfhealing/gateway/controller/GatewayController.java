package com.selfhealing.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/gateway")
public class GatewayController {

    @GetMapping("/health")
    public Mono<Map<String, Object>> getGatewayHealth() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "api-gateway");
        status.put("status", "UP");
        status.put("port", 8080);
        status.put("timestamp", LocalDateTime.now().toString());
        return Mono.just(status);
    }
}
