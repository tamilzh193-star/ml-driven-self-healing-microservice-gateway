package com.selfhealing.gateway.filter;

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

/**
 * Security filter protecting administrative and fault injection endpoints.
 * Enforces X-Admin-Key validation on chaos, reset, and trigger endpoints,
 * and blocks unauthenticated direct routing to downstream chaos APIs.
 */
@Component
public class AdminAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    @Value("${security.admin.key.enabled:true}")
    private boolean adminAuthEnabled = true;

    @Value("${admin.api.key:${ADMIN_API_KEY:${ADMIN_KEY:}}}")
    private String adminApiKey = "";

    public void setAdminAuthEnabled(boolean adminAuthEnabled) {
        this.adminAuthEnabled = adminAuthEnabled;
    }

    public boolean isAdminAuthEnabled() {
        return adminAuthEnabled;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    @Override
    public int getOrder() {
        return -50; // Execute before route forwarding and remediation filter
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (!isProtectedPath(path)) {
            return chain.filter(exchange);
        }

        String providedKey = exchange.getRequest().getHeaders().getFirst("X-Admin-Key");

        if (adminAuthEnabled) {
            // Fail safely if admin auth is enabled but no server key is configured
            if (adminApiKey == null || adminApiKey.trim().isEmpty()) {
                log.error("[ADMIN AUTH] Security misconfiguration: admin auth enabled but ADMIN_API_KEY not set. Rejecting protected access to '{}'", path);
                return rejectUnauthorized(exchange, HttpStatus.FORBIDDEN, "Forbidden: Server administrator key is not configured");
            }

            if (providedKey == null || providedKey.trim().isEmpty()) {
                log.warn("[ADMIN AUTH] Rejected unauthenticated request to protected path '{}' (Missing X-Admin-Key header)", path);
                return rejectUnauthorized(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized: Valid X-Admin-Key header is required for administrative operations");
            }

            if (!providedKey.equals(adminApiKey)) {
                log.warn("[ADMIN AUTH] Rejected unauthorized request to protected path '{}' (Invalid X-Admin-Key provided)", path);
                return rejectUnauthorized(exchange, HttpStatus.FORBIDDEN, "Forbidden: Invalid X-Admin-Key provided");
            }
        } else if (providedKey != null && !providedKey.trim().isEmpty() && !providedKey.equals(adminApiKey)) {
            // If explicitly disabled but a non-matching key was supplied, fail-safe reject
            log.warn("[ADMIN AUTH] Rejected mismatched X-Admin-Key on path '{}'", path);
            return rejectUnauthorized(exchange, HttpStatus.FORBIDDEN, "Forbidden: Invalid X-Admin-Key provided");
        }

        return chain.filter(exchange);
    }

    public boolean isProtectedPath(String path) {
        if (path == null) {
            return false;
        }
        // Protect any fault / chaos injection path (direct or forwarded through gateway)
        if (path.contains("/chaos")) {
            return true;
        }
        // Protect administrative reset operations across anomaly, healing, db, or dashboard
        if (path.endsWith("/reset") || path.contains("/reset/")) {
            return true;
        }
        // Protect manual healing trigger endpoints
        if (path.contains("/healing/trigger")) {
            return true;
        }
        // Protect dedicated admin endpoints if present
        if (path.startsWith("/admin") || path.startsWith("/api/admin")) {
            return true;
        }
        return false;
    }

    private Mono<Void> rejectUnauthorized(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String json = String.format("{\"error\":\"%s\",\"status\":%d,\"message\":\"%s\"}",
                status.getReasonPhrase(), status.value(), message);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
