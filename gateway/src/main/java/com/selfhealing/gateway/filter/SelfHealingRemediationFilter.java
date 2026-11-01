package com.selfhealing.gateway.filter;

import com.selfhealing.gateway.service.RemediationExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Spring Cloud Gateway GlobalFilter dynamically applying runtime Traffic Redirection
 * and Dynamic Rate Limiting whenever autonomous remediation is active on a service route.
 */
@Component
public class SelfHealingRemediationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingRemediationFilter.class);

    private final RemediationExecutionService remediationExecutionService;

    public SelfHealingRemediationFilter(RemediationExecutionService remediationExecutionService) {
        this.remediationExecutionService = remediationExecutionService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Do not intercept gateway internal management, chaos, or actuator endpoints
        if (path.startsWith("/gateway") || path.startsWith("/actuator") || path.contains("/chaos")) {
            return chain.filter(exchange);
        }

        String serviceName = resolveServiceName(path);
        if (serviceName == null) {
            return chain.filter(exchange);
        }

        // 1. Check if Traffic Redirection / Fallback mode is active
        if (remediationExecutionService.isTrafficRedirectionActive(serviceName)) {
            log.info("[FILTER: REDIRECTION] Intercepted request to '{}' -> routing to safe fallback response", path);
            return respondWithFallback(exchange, serviceName);
        }

        // 2. Check if Dynamic Rate Limiting is active
        if (remediationExecutionService.isRateLimitActive(serviceName)) {
            boolean allowed = remediationExecutionService.checkRateLimitAllowed(serviceName);
            if (!allowed) {
                log.warn("[FILTER: RATE_LIMIT] Throttled request to '{}' -> HTTP 429 Too Many Requests", path);
                return respondWithRateLimitExceeded(exchange, serviceName);
            }
        }

        return chain.filter(exchange);
    }

    private String resolveServiceName(String path) {
        if (path.startsWith("/orders") || path.startsWith("/order")) {
            return "order-service";
        } else if (path.startsWith("/payments") || path.startsWith("/payment")) {
            return "payment-service";
        } else if (path.startsWith("/inventory") || path.startsWith("/invetory")) {
            return "inventory-service";
        }
        return null;
    }

    @Value("${gateway.fallback.http-status:200}")
    private int fallbackHttpStatus;

    private Mono<Void> respondWithFallback(ServerWebExchange exchange, String serviceName) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(fallbackHttpStatus == 503 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", "5");
        response.getHeaders().set("X-Self-Healing", "active");

        String json = String.format(
                "{\"status\":\"FALLBACK_REDIRECTION\",\"service\":\"%s\",\"message\":\"Self-Healing Gateway active: traffic redirected to safe degraded fallback while service recovers\",\"timestamp\":\"%s\"}",
                serviceName, Instant.now()
        );

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> respondWithRateLimitExceeded(ServerWebExchange exchange, String serviceName) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String json = String.format(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"service\":\"%s\",\"message\":\"Self-Healing Gateway dynamic rate limiting active to prevent service collapse\",\"timestamp\":\"%s\"}",
                serviceName, Instant.now()
        );

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
