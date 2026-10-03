package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.HealingActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes physical remediation actions (RESTART, TRAFFIC_REDIRECTION, RATE_LIMIT)
 * across target microservices and maintains active gateway runtime remediation states.
 */
@Service
public class RemediationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RemediationExecutionService.class);

    private final WebClient webClient;
    private final Map<String, String> targetServiceUrls = new ConcurrentHashMap<>();

    // Active traffic redirection states: serviceName -> true
    private final Set<String> activeRedirections = ConcurrentHashMap.newKeySet();

    // Active rate limits: serviceName -> max requests per second
    private final Map<String, Integer> activeRateLimits = new ConcurrentHashMap<>();

    // Rolling request counter per second for rate limiting: serviceName -> (timestampSec -> count)
    private final Map<String, AtomicInteger> currentSecondRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> currentSecondTimestamp = new ConcurrentHashMap<>();

    public RemediationExecutionService() {
        this.webClient = WebClient.builder().build();

        targetServiceUrls.put("order-service", "http://localhost:8081");
        targetServiceUrls.put("payment-service", "http://localhost:8082");
        targetServiceUrls.put("inventory-service", "http://localhost:8083");
    }

    /**
     * Executes the specified remediation action against the target microservice.
     */
    public boolean executeRemediation(String serviceName, HealingActionType actionType) {
        String baseUrl = targetServiceUrls.get(serviceName);
        if (baseUrl == null) {
            log.error("Unknown target service: {}", serviceName);
            return false;
        }

        log.info("[HEALING DISPATCH] Executing {} remediation on service '{}' ({})", actionType, serviceName, baseUrl);

        try {
            switch (actionType) {
                case RESTART:
                    return executeRestart(serviceName, baseUrl);

                case TRAFFIC_REDIRECTION:
                    return executeTrafficRedirection(serviceName, baseUrl);

                case RATE_LIMIT:
                    return executeRateLimit(serviceName, baseUrl);

                default:
                    log.warn("Unsupported remediation action: {}", actionType);
                    return false;
            }
        } catch (Exception e) {
            log.error("Failed to execute {} on {}: {}", actionType, serviceName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Executes a service restart / recovery reset.
     * Clears all chaos fault injections, memory leak buffers, and hung threads.
     */
    private boolean executeRestart(String serviceName, String baseUrl) {
        log.info("[HEALING: RESTART] Sending recovery reset command to {}/chaos/reset", baseUrl);
        try {
            webClient.post()
                    .uri(baseUrl + "/chaos/reset")
                    .retrieve()
                    .toBodilessEntity()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(5000));

            // Clean up any remaining traffic redirection or rate limiting for this service
            activeRedirections.remove(serviceName);
            activeRateLimits.remove(serviceName);

            log.info("[HEALING: RESTART] Successfully executed restart/recovery for service '{}'", serviceName);
            return true;
        } catch (Exception e) {
            log.error("[HEALING: RESTART] Failed to reset/restart service '{}': {}", serviceName, e.getMessage());
            return false;
        }
    }

    /**
     * Activates traffic redirection to safe fallback and signals downstream service to clear backlog.
     */
    private boolean executeTrafficRedirection(String serviceName, String baseUrl) {
        log.info("[HEALING: TRAFFIC_REDIRECTION] Activating gateway fallback routing for '{}'", serviceName);
        activeRedirections.add(serviceName);

        // Also signal downstream service to clear any latency delays or hung threads
        try {
            webClient.post()
                    .uri(baseUrl + "/chaos/reset")
                    .retrieve()
                    .toBodilessEntity()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(3000));
        } catch (Exception e) {
            log.warn("[HEALING: TRAFFIC_REDIRECTION] Downstream reset signal failed: {}", e.getMessage());
        }

        return true;
    }

    /**
     * Activates dynamic gateway rate limiting (default: 2 requests/sec) to prevent resource starvation.
     */
    private boolean executeRateLimit(String serviceName, String baseUrl) {
        log.info("[HEALING: RATE_LIMIT] Activating gateway dynamic rate limiting for '{}' (limit: 2 req/s)", serviceName);
        activeRateLimits.put(serviceName, 2);

        // Signal service to release any CPU workload threads or clear memory pressure
        try {
            webClient.post()
                    .uri(baseUrl + "/chaos/reset")
                    .retrieve()
                    .toBodilessEntity()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(3000));
        } catch (Exception e) {
            log.warn("[HEALING: RATE_LIMIT] Downstream reset signal failed: {}", e.getMessage());
        }

        return true;
    }

    /**
     * Deactivates all temporary runtime remediations (traffic redirection, rate limiting)
     * once post-healing recovery is verified.
     */
    public void deactivateRemediations(String serviceName) {
        boolean hadRedirection = activeRedirections.remove(serviceName);
        boolean hadRateLimit = (activeRateLimits.remove(serviceName) != null);

        if (hadRedirection || hadRateLimit) {
            log.info("[HEALING: RESTORE] Deactivated temporary mitigations (redirection={}, rateLimit={}) for service '{}'",
                    hadRedirection, hadRateLimit, serviceName);
        }
    }

    public boolean isTrafficRedirectionActive(String serviceName) {
        return activeRedirections.contains(serviceName);
    }

    public boolean isRateLimitActive(String serviceName) {
        return activeRateLimits.containsKey(serviceName);
    }

    /**
     * Checks if an incoming request for this service is permitted under active rate limiting.
     * Returns true if allowed, false if rate limited (HTTP 429).
     */
    public boolean checkRateLimitAllowed(String serviceName) {
        Integer limit = activeRateLimits.get(serviceName);
        if (limit == null) {
            return true;
        }

        long currentSec = System.currentTimeMillis() / 1000;
        Long lastSec = currentSecondTimestamp.get(serviceName);

        if (lastSec == null || lastSec != currentSec) {
            currentSecondTimestamp.put(serviceName, currentSec);
            currentSecondRequests.put(serviceName, new AtomicInteger(1));
            return true;
        }

        AtomicInteger counter = currentSecondRequests.get(serviceName);
        if (counter == null) {
            currentSecondRequests.put(serviceName, new AtomicInteger(1));
            return true;
        }

        int count = counter.incrementAndGet();
        return count <= limit;
    }

    public Set<String> getActiveRedirections() {
        return new HashSet<>(activeRedirections);
    }

    public Map<String, Integer> getActiveRateLimits() {
        return new HashMap<>(activeRateLimits);
    }

    public void resetAll() {
        activeRedirections.clear();
        activeRateLimits.clear();
        currentSecondRequests.clear();
        currentSecondTimestamp.clear();
        log.info("Reset all runtime remediation states (redirections and rate limits).");
    }
}
