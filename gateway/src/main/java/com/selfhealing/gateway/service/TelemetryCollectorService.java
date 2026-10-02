package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.gateway.model.ServiceTelemetry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Duration TIMEOUT = Duration.ofMillis(3000);

    private final WebClient webClient;

    @Value("${telemetry.collection.enabled:true}")
    private boolean collectionEnabled;

    @Value("${telemetry.collection.csv-path:../dataset/telemetry.csv}")
    private String csvPath;

    // Track configured service URLs: serviceName -> baseUrl
    private final Map<String, String> targetServices = new LinkedHashMap<>();

    // Keep latest telemetry per service
    private final Map<String, ServiceTelemetry> latestTelemetryMap = new ConcurrentHashMap<>();

    // In-memory rolling history (max 100 records)
    private final Deque<ServiceTelemetry> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 100;

    // Track previous counts to calculate request rate deltas
    private final Map<String, Long> previousRequestCount = new ConcurrentHashMap<>();
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
        targetServices.put("order-service", "http://localhost:8081");
        targetServices.put("payment-service", "http://localhost:8082");
        targetServices.put("inventory-service", "http://localhost:8083");

        initCsvFile();
        log.info("TelemetryCollectorService initialized with targets: {}", targetServices.keySet());
    }

    private synchronized void initCsvFile() {
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

            // 4. HTTP Metrics (latency ms, error rate %, request rate req/sec)
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
                // Convert 0.0 - 1.0 fraction to 0.0 - 100.0 percentage
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
            // Query heap memory explicitly to ensure reliable measurement across JVMs
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

            // Fallback: If heap max is negative (unrestricted) or zero, query jvm.memory.committed
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
        try {
            JsonNode baseNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/http.server.requests")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            if (baseNode != null && baseNode.has("measurements")) {
                long totalCount = 0;
                double totalTimeSec = 0.0;

                for (JsonNode m : baseNode.get("measurements")) {
                    String stat = m.get("statistic").asText("");
                    if ("COUNT".equalsIgnoreCase(stat)) {
                        totalCount = m.get("value").asLong(0);
                    } else if ("TOTAL_TIME".equalsIgnoreCase(stat)) {
                        totalTimeSec = m.get("value").asDouble(0.0);
                    }
                }

                // Mean latency in ms = (totalTimeSec / totalCount) * 1000
                if (totalCount > 0) {
                    result.latencyMs = Math.round((totalTimeSec / totalCount) * 1000.0 * 100.0) / 100.0;
                }

                // Request rate calculation from delta count between intervals
                long now = System.currentTimeMillis();
                Long prevCount = previousRequestCount.get(serviceName);
                Long prevTime = previousTimestampMillis.get(serviceName);

                if (prevCount != null && prevTime != null && now > prevTime) {
                    long deltaCount = totalCount - prevCount;
                    if (deltaCount < 0) deltaCount = 0;
                    double deltaSec = (now - prevTime) / 1000.0;
                    if (deltaSec > 0.0) {
                        result.requestRate = Math.round((deltaCount / deltaSec) * 100.0) / 100.0;
                    }
                } else {
                    result.requestRate = 0.0;
                }

                previousRequestCount.put(serviceName, totalCount);
                previousTimestampMillis.put(serviceName, now);

                // Error rate calculation: Check for 5xx errors using available tags
                long error5xxCount = 0;
                boolean hasServerErrorTag = false;

                if (baseNode.has("availableTags") && baseNode.get("availableTags").isArray()) {
                    for (JsonNode tagNode : baseNode.get("availableTags")) {
                        String tagName = tagNode.get("tag").asText("");
                        if ("outcome".equalsIgnoreCase(tagName) && tagNode.has("values")) {
                            for (JsonNode val : tagNode.get("values")) {
                                if ("SERVER_ERROR".equalsIgnoreCase(val.asText(""))) {
                                    hasServerErrorTag = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (hasServerErrorTag) {
                    try {
                        JsonNode errorNode = webClient.get()
                                .uri(baseUrl + "/actuator/metrics/http.server.requests?tag=outcome:SERVER_ERROR")
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
                    } catch (Exception e) {
                        log.debug("Error tag query for {} not available: {}", serviceName, e.getMessage());
                    }
                }

                if (totalCount > 0 && error5xxCount > 0) {
                    double errPercent = ((double) error5xxCount / totalCount) * 100.0;
                    result.errorRate = Math.round(errPercent * 100.0) / 100.0;
                } else {
                    result.errorRate = 0.0;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch http.server.requests for {}: {}", serviceName, e.getMessage());
        }
        return result;
    }

    private synchronized void appendCsvRecord(ServiceTelemetry telemetry) {
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
