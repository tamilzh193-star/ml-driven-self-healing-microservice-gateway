package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.HealingActionType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes physical remediation actions across target microservices and maintains
 * active gateway runtime remediation states:
 *
 * 1. RESTART:
 *    - Production / Docker mode: Performs GENUINE container restart via Docker Engine API
 *      (/var/run/docker.sock or Docker CLI) which restarts the actual service container process.
 *    - Standalone local dev mode: Safe development fallback resetting faults via /chaos/reset.
 *
 * 2. TRAFFIC_REDIRECTION:
 *    - Gateway Fallback / Protection Mode: Intercepts downstream traffic to prevent cascading
 *      failures, sheds load, and shields degrading services during recovery without falsely
 *      claiming multi-instance round-robin when operating in single-instance mode.
 *
 * 3. RATE_LIMIT:
 *    - Gateway Dynamic Ingress Throttling: Enforces strict request-rate limits (e.g. 2 req/s)
 *      to eliminate CPU saturation and buffer queue starvation.
 */
@Service
public class RemediationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RemediationExecutionService.class);

    private final WebClient webClient;
    private final Map<String, String> targetServiceUrls = new ConcurrentHashMap<>();

    @Value("${order.service.url:http://localhost:8081}")
    private String orderServiceUrl;

    @Value("${payment.service.url:http://localhost:8082}")
    private String paymentServiceUrl;

    @Value("${inventory.service.url:http://localhost:8083}")
    private String inventoryServiceUrl;

    @Value("${docker.container.mode:false}")
    private boolean dockerContainerMode;

    @Value("${docker.socket.path:/var/run/docker.sock}")
    private String dockerSocketPath;

    @Value("${healing.restart.mode:auto}")
    private String restartMode;

    private final DockerRestartExecutor dockerRestartExecutor;
    private final SoftResetExecutor softResetExecutor;
    private final Map<String, RestartResult> lastRestartResults = new ConcurrentHashMap<>();

    // Active traffic redirection / gateway protection states: serviceName -> true
    private final Set<String> activeRedirections = ConcurrentHashMap.newKeySet();

    // Active rate limits: serviceName -> max requests per second
    private final Map<String, Integer> activeRateLimits = new ConcurrentHashMap<>();

    // Rolling request counter per second for rate limiting: serviceName -> (timestampSec -> count)
    private final Map<String, AtomicInteger> currentSecondRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> currentSecondTimestamp = new ConcurrentHashMap<>();

    public RemediationExecutionService(DockerRestartExecutor dockerRestartExecutor, SoftResetExecutor softResetExecutor) {
        this.dockerRestartExecutor = dockerRestartExecutor;
        this.softResetExecutor = softResetExecutor;
        this.webClient = WebClient.builder().build();
    }

    @PostConstruct
    public void init() {
        targetServiceUrls.put("order-service", orderServiceUrl);
        targetServiceUrls.put("payment-service", paymentServiceUrl);
        targetServiceUrls.put("inventory-service", inventoryServiceUrl);
        log.info("RemediationExecutionService initialized with endpoints: {}, Docker Available: {}, Restart Mode: {}",
                targetServiceUrls, isDockerEnvironment(), restartMode);
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
     * Executes a genuine service restart in Docker, or safe development fallback in local dev.
     */
    private boolean executeRestart(String serviceName, String baseUrl) {
        log.info("[HEALING: RESTART] Initiating restart remediation for service '{}' (baseUrl={}, configuredMode={})",
                serviceName, baseUrl, restartMode);

        RestartExecutor executor;
        if ("docker".equalsIgnoreCase(restartMode)) {
            executor = dockerRestartExecutor;
        } else if ("soft".equalsIgnoreCase(restartMode)) {
            executor = softResetExecutor;
        } else {
            // "auto" mode: use Docker if available, otherwise fallback to soft reset
            if (dockerRestartExecutor.isAvailable()) {
                executor = dockerRestartExecutor;
            } else {
                executor = softResetExecutor;
            }
        }

        RestartResult result = executor.restart(serviceName, baseUrl);
        lastRestartResults.put(serviceName, result);

        if (result.isSuccess()) {
            activeRedirections.remove(serviceName);
            activeRateLimits.remove(serviceName);
            log.info("[HEALING: RESTART] Restart successfully executed in mode {} for service '{}': {}",
                    result.getMode(), serviceName, result.getDetails());
            return true;
        } else {
            log.error("[HEALING: RESTART] Restart failed in mode {} for service '{}': {}",
                    result.getMode(), serviceName, result.getDetails());
            return false;
        }
    }

    public RestartResult getLastRestartResult(String serviceName) {
        return lastRestartResults.get(serviceName);
    }

    public boolean restartDockerContainer(String serviceName) {
        String baseUrl = targetServiceUrls.get(serviceName);
        RestartResult res = dockerRestartExecutor.restart(serviceName, baseUrl);
        return res.isSuccess();
    }

    public boolean isDockerEnvironment() {
        return dockerRestartExecutor.isAvailable();
    }

    /**
     * Activates Gateway Fallback / Protection Mode (Graceful Degradation).
     * Intercepts downstream traffic to prevent cascading failures, sheds load,
     * and clears downstream backlog while initiating service recovery.
     */
    private boolean executeTrafficRedirection(String serviceName, String baseUrl) {
        log.info("[HEALING: TRAFFIC_REDIRECTION] Activating gateway fallback/protection mode for '{}'", serviceName);
        activeRedirections.add(serviceName);

        // Signal downstream service to clear any latency delays or hung threads
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
     * Activates dynamic gateway rate limiting (default: 2 requests/sec) to eliminate
     * CPU saturation and buffer queue starvation.
     */
    private boolean executeRateLimit(String serviceName, String baseUrl) {
        log.info("[HEALING: RATE_LIMIT] Activating gateway dynamic rate limiting for '{}' (limit: 2 req/s)", serviceName);
        activeRateLimits.put(serviceName, 2);

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
            log.info("[HEALING: RESTORE] Deactivated temporary mitigations (gatewayFallback={}, rateLimit={}) for service '{}'",
                    hadRedirection, hadRateLimit, serviceName);
        }
    }

    public boolean isTrafficRedirectionActive(String serviceName) {
        return activeRedirections.contains(serviceName);
    }

    public boolean isRateLimitActive(String serviceName) {
        return activeRateLimits.containsKey(serviceName);
    }

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
        log.info("Reset all runtime remediation states (gateway fallbacks and rate limits).");
    }
}
