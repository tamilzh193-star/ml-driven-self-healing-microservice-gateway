package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.gateway.model.ServiceTelemetry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class TelemetryCollectorService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryCollectorService.class);
    private static final String CSV_HEADER = "timestamp,service,cpu,memory,latency,error_rate,request_rate,health_status";
    private static final Duration TIMEOUT = Duration.ofMillis(10000);

    private final WebClient webClient;

    @Value("${telemetry.collection.enabled:true}")
    private boolean collectionEnabled;

    @Value("${telemetry.collection.csv-enabled:false}")
    private boolean csvEnabled;

    @Value("${telemetry.collection.csv-path:runtime/telemetry_live.csv}")
    private String csvPath;

    @Value("${order.service.url:http://localhost:8081}")
    private String orderServiceUrl;

    @Value("${payment.service.url:http://localhost:8082}")
    private String paymentServiceUrl;

    @Value("${inventory.service.url:http://localhost:8083}")
    private String inventoryServiceUrl;

    @Autowired(required = false)
    private MlClientService mlClientService;

    @Autowired(required = false)
    private DatabasePersistenceService databasePersistenceService;

    // Track configured service URLs: serviceName -> baseUrl
    private final Map<String, String> targetServices = new LinkedHashMap<>();

    // Track primary business endpoint per service for active latency and error observation
    private final Map<String, String> businessEndpoints = new LinkedHashMap<>();

    // Keep latest telemetry per service
    private final Map<String, ServiceTelemetry> latestTelemetryMap = new ConcurrentHashMap<>();

    // In-memory rolling history (max 100 records)
    private final Deque<ServiceTelemetry> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 100;

    // Track previous business request metrics to calculate delta request rate and delta latency
    private final Map<String, Long> previousRequestCount = new ConcurrentHashMap<>();
    private final Map<String, Double> previousTotalTimeSec = new ConcurrentHashMap<>();
    private final Map<String, Long> previousTimestampMillis = new ConcurrentHashMap<>();

    public TelemetryCollectorService() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    public TelemetryCollectorService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @PostConstruct
    public void init() {
        targetServices.put("order-service", orderServiceUrl);
        targetServices.put("payment-service", paymentServiceUrl);
        targetServices.put("inventory-service", inventoryServiceUrl);

        businessEndpoints.put("order-service", "/orders");
        businessEndpoints.put("payment-service", "/payments");
        businessEndpoints.put("inventory-service", "/inventory");

        initCsvFile();
        log.info("TelemetryCollectorService initialized with targets: {}", targetServices);
    }

    private synchronized void initCsvFile() {
        if (!csvEnabled) {
            return;
        }
        try {
            File file = new File(csvPath);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            if (!file.exists() || file.length() == 0) {
                try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
                    writer.println(CSV_HEADER);
                }
                log.info("Initialized telemetry CSV file at {}", file.getAbsolutePath());
            }
        } catch (IOException e) {
            log.error("Failed to initialize telemetry CSV file: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedRateString = "${telemetry.collection.interval-ms:5000}")
    public void scheduledCollection() {
        if (!collectionEnabled) {
            return;
        }
        collectAll();
    }

    public synchronized List<ServiceTelemetry> collectAll() {
        List<ServiceTelemetry> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : targetServices.entrySet()) {
            String serviceName = entry.getKey();
            String baseUrl = entry.getValue();
            ServiceTelemetry telemetry = collectFromService(serviceName, baseUrl);
            results.add(telemetry);

            latestTelemetryMap.put(serviceName, telemetry);
            history.addLast(telemetry);
            while (history.size() > MAX_HISTORY) {
                history.pollFirst();
            }

            appendCsvRecord(telemetry);
        }

        // Phase 10: Dispatch collected telemetry batch to ML Service for anomaly detection
        if (mlClientService != null && !results.isEmpty()) {
            try {
                mlClientService.evaluateBatch(results);
            } catch (Exception e) {
                log.warn("Error forwarding telemetry to ML service: {}", e.getMessage());
            }
        }

        // Phase 13: Asynchronously persist telemetry batch to relational database
        if (databasePersistenceService != null && !results.isEmpty()) {
            try {
                databasePersistenceService.persistTelemetryBatchAsync(results).subscribe();
            } catch (Exception e) {
                log.debug("Database persistence async dispatch error: {}", e.getMessage());
            }
        }

        return results;
    }

    public ServiceTelemetry collectFromService(String serviceName, String baseUrl) {
        try {
            // 1. Health check
            String healthStatus = fetchHealthStatus(serviceName, baseUrl);

            if (!"UP".equalsIgnoreCase(healthStatus)) {
                log.warn("Service {} at {} reported health status: {}", serviceName, baseUrl, healthStatus);
                return new ServiceTelemetry(serviceName, 0.0, 0.0, 0.0, 100.0, 0.0, healthStatus != null ? healthStatus : "DOWN");
            }

            // 2. CPU Usage (0-100 percentage)
            double cpuPercent = fetchCpuUsage(serviceName, baseUrl);

            // 3. JVM Memory Usage (% based on heap used / max)
            double memoryPercent = fetchMemoryUsage(serviceName, baseUrl);

            // 4. HTTP Metrics (business response latency ms, error rate %, request rate req/sec)
            HttpMetrics metrics = fetchHttpMetrics(serviceName, baseUrl);

            ServiceTelemetry telemetry = new ServiceTelemetry(serviceName, cpuPercent, memoryPercent,
                    metrics.latencyMs, metrics.errorRate, metrics.requestRate, healthStatus);

            log.debug("Telemetry collected for {}: cpu={}%, mem={}%, lat={}ms, err={}%",
                    serviceName, cpuPercent, memoryPercent, metrics.latencyMs, metrics.errorRate);

            return telemetry;

        } catch (Exception e) {
            log.error("Unexpected error collecting telemetry from {} ({}): {}", serviceName, baseUrl, e.getMessage(), e);
            return new ServiceTelemetry(serviceName, 0.0, 0.0, 0.0, 100.0, 0.0, "DOWN");
        }
    }

    private String fetchHealthStatus(String serviceName, String baseUrl) {
        try {
            JsonNode node = webClient.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            if (node != null && node.has("status")) {
                return node.get("status").asText();
            }
        } catch (WebClientResponseException e) {
            log.warn("Actuator /actuator/health for {} returned HTTP {}: {}", serviceName, e.getStatusCode(), e.getMessage());
            try {
                JsonNode body = e.getResponseBodyAs(JsonNode.class);
                if (body != null && body.has("status")) {
                    return body.get("status").asText();
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.warn("Failed to reach /actuator/health for {} ({}): {}", serviceName, baseUrl, e.getMessage());
        }
        return "DOWN";
    }

    private double fetchCpuUsage(String serviceName, String baseUrl) {
        try {
            JsonNode node = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/process.cpu.usage")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            if (node != null && node.has("measurements") && node.get("measurements").isArray() && !node.get("measurements").isEmpty()) {
                double raw = node.get("measurements").get(0).get("value").asDouble(0.0);
                if (raw < 0.0) {
                    raw = 0.0;
                }
                double percent = raw * 100.0;
                return Math.round(percent * 100.0) / 100.0;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch process.cpu.usage for {}: {}", serviceName, e.getMessage());
        }
        return 0.0;
    }

    private double fetchMemoryUsage(String serviceName, String baseUrl) {
        try {
            JsonNode usedNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/jvm.memory.used?tag=area:heap")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            JsonNode maxNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/jvm.memory.max?tag=area:heap")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            double used = 0.0;
            double max = 0.0;

            if (usedNode != null && usedNode.has("measurements") && !usedNode.get("measurements").isEmpty()) {
                used = usedNode.get("measurements").get(0).get("value").asDouble(0.0);
            }

            if (maxNode != null && maxNode.has("measurements") && !maxNode.get("measurements").isEmpty()) {
                max = maxNode.get("measurements").get(0).get("value").asDouble(0.0);
            }

            if (max <= 0.0) {
                JsonNode committedNode = webClient.get()
                        .uri(baseUrl + "/actuator/metrics/jvm.memory.committed?tag=area:heap")
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(TIMEOUT)
                        .block();
                if (committedNode != null && committedNode.has("measurements") && !committedNode.get("measurements").isEmpty()) {
                    max = committedNode.get("measurements").get(0).get("value").asDouble(0.0);
                }
            }

            if (used > 0.0 && max > 0.0) {
                double percent = (used / max) * 100.0;
                if (percent > 100.0) percent = 100.0;
                return Math.round(percent * 100.0) / 100.0;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch jvm memory for {}: {}", serviceName, e.getMessage());
        }
        return 0.0;
    }

    private HttpMetrics fetchHttpMetrics(String serviceName, String baseUrl) {
        HttpMetrics result = new HttpMetrics();
        String businessPath = businessEndpoints.getOrDefault(serviceName, "/");

        // Step 1: Probe the business endpoint to capture real live round-trip latency and fault impact
        double probeLatencyMs = 0.0;
        boolean probe5xxError = false;
        long probeStart = System.nanoTime();
        try {
            webClient.get()
                    .uri(baseUrl + businessPath)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
            probeLatencyMs = (System.nanoTime() - probeStart) / 1_000_000.0;
        } catch (WebClientResponseException e) {
            probeLatencyMs = (System.nanoTime() - probeStart) / 1_000_000.0;
            if (e.getStatusCode().is5xxServerError()) {
                probe5xxError = true;
            }
            log.debug("Probe to {}{} returned HTTP {}", serviceName, businessPath, e.getStatusCode());
        } catch (Exception e) {
            probeLatencyMs = (System.nanoTime() - probeStart) / 1_000_000.0;
            log.warn("Probe request to {}{} encountered: {}", serviceName, businessPath, e.getMessage());
        }

        // Step 2: Query Actuator for business endpoint metrics (specifically filtering out /actuator and /chaos)
        long currentBusinessCount = 0;
        double currentBusinessTotalTimeSec = 0.0;
        long error5xxCount = 0;

        try {
            JsonNode taggedNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/http.server.requests?tag=uri:" + businessPath)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            if (taggedNode != null && taggedNode.has("measurements")) {
                for (JsonNode m : taggedNode.get("measurements")) {
                    String stat = m.get("statistic").asText("");
                    if ("COUNT".equalsIgnoreCase(stat)) {
                        currentBusinessCount = m.get("value").asLong(0);
                    } else if ("TOTAL_TIME".equalsIgnoreCase(stat)) {
                        currentBusinessTotalTimeSec = m.get("value").asDouble(0.0);
                    }
                }
            }
        } catch (WebClientResponseException.NotFound ignored) {
            // Tag not yet initialized
        } catch (Exception e) {
            log.debug("Tagged actuator query failed for {}: {}", serviceName, e.getMessage());
        }

        // Step 3: Check for 5xx errors across business endpoints
        try {
            JsonNode errorNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/http.server.requests?tag=uri:" + businessPath + "&tag=outcome:SERVER_ERROR")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            if (errorNode != null && errorNode.has("measurements")) {
                for (JsonNode em : errorNode.get("measurements")) {
                    if ("COUNT".equalsIgnoreCase(em.get("statistic").asText(""))) {
                        error5xxCount = em.get("value").asLong(0);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Step 4: Calculate interval delta latency, throughput, and error rate
        long now = System.currentTimeMillis();
        Long prevCount = previousRequestCount.get(serviceName);
        Double prevTimeSec = previousTotalTimeSec.get(serviceName);
        Long prevTimeMillis = previousTimestampMillis.get(serviceName);

        if (prevCount != null && prevTimeSec != null && prevTimeMillis != null && now > prevTimeMillis) {
            long deltaCount = currentBusinessCount - prevCount;
            double deltaTimeSec = currentBusinessTotalTimeSec - prevTimeSec;
            double deltaSeconds = (now - prevTimeMillis) / 1000.0;

            if (deltaCount > 0 && deltaTimeSec > 0.0) {
                // Mean latency of actual requests executed in this interval
                result.latencyMs = Math.round((deltaTimeSec / deltaCount) * 1000.0 * 100.0) / 100.0;
            } else {
                // If no delta available, use the live measured probe latency
                result.latencyMs = Math.round(probeLatencyMs * 100.0) / 100.0;
            }

            if (deltaSeconds > 0.0 && deltaCount >= 0) {
                result.requestRate = Math.round((deltaCount / deltaSeconds) * 100.0) / 100.0;
            }
        } else {
            // First observation: use live probe latency
            result.latencyMs = Math.round(probeLatencyMs * 100.0) / 100.0;
            result.requestRate = 0.0;
        }

        previousRequestCount.put(serviceName, currentBusinessCount);
        previousTotalTimeSec.put(serviceName, currentBusinessTotalTimeSec);
        previousTimestampMillis.put(serviceName, now);

        // Step 5: Error Rate calculation
        if (currentBusinessCount > 0 && error5xxCount > 0) {
            double errPercent = ((double) error5xxCount / currentBusinessCount) * 100.0;
            result.errorRate = Math.round(errPercent * 100.0) / 100.0;
        } else if (probe5xxError) {
            result.errorRate = 100.0;
        } else {
            result.errorRate = 0.0;
        }

        return result;
    }

    private synchronized void appendCsvRecord(ServiceTelemetry telemetry) {
        if (!csvEnabled) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvPath, true))) {
            writer.println(telemetry.toCsvRow());
        } catch (IOException e) {
            log.error("Failed to write telemetry record to CSV: {}", e.getMessage(), e);
        }
    }

    public Map<String, ServiceTelemetry> getLatestTelemetry() {
        return Collections.unmodifiableMap(latestTelemetryMap);
    }

    public List<ServiceTelemetry> getHistory() {
        return new ArrayList<>(history);
    }

    private static class HttpMetrics {
        double latencyMs = 0.0;
        double errorRate = 0.0;
        double requestRate = 0.0;
    }
}
