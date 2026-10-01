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
import reactor.core.publisher.Mono;

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

    public TelemetryCollectorService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
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
            log.error("Failed to initialize telemetry CSV file: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRateString = "${telemetry.collection.interval-ms:5000}")
    public void scheduledCollection() {
        if (!collectionEnabled) {
            return;
        }
        collectAll();
    }

    public List<ServiceTelemetry> collectAll() {
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
            String healthStatus = fetchHealthStatus(baseUrl);

            if ("DOWN".equals(healthStatus)) {
                return new ServiceTelemetry(serviceName, 0.0, 0.0, 0.0, 100.0, 0.0, "DOWN");
            }

            // 2. CPU Usage
            double cpuPercent = fetchCpuUsage(baseUrl);

            // 3. JVM Memory Usage
            double memoryPercent = fetchMemoryUsage(baseUrl);

            // 4. HTTP Metrics (latency, error rate, request rate)
            HttpMetrics metrics = fetchHttpMetrics(serviceName, baseUrl);

            return new ServiceTelemetry(serviceName, cpuPercent, memoryPercent,
                    metrics.latencyMs, metrics.errorRate, metrics.requestRate, healthStatus);

        } catch (Exception e) {
            log.warn("Telemetry collection failed for {}: {}", serviceName, e.getMessage());
            return new ServiceTelemetry(serviceName, 0.0, 0.0, 0.0, 100.0, 0.0, "DOWN");
        }
    }

    private String fetchHealthStatus(String baseUrl) {
        try {
            JsonNode node = webClient.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(2000))
                    .onErrorReturn(null)
                    .block();

            if (node != null && node.has("status")) {
                return node.get("status").asText();
            }
        } catch (Exception ignored) {
        }
        return "DOWN";
    }

    private double fetchCpuUsage(String baseUrl) {
        try {
            JsonNode node = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/process.cpu.usage")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(2000))
                    .onErrorReturn(null)
                    .block();

            if (node != null && node.has("measurements") && node.get("measurements").isArray()) {
                double raw = node.get("measurements").get(0).get("value").asDouble(0.0);
                if (raw < 0) raw = 0.0;
                return Math.round(raw * 10000.0) / 100.0; // scale 0-1 to percentage (e.g. 15.45%)
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private double fetchMemoryUsage(String baseUrl) {
        try {
            JsonNode usedNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/jvm.memory.used")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(2000))
                    .onErrorReturn(null)
                    .block();

            JsonNode maxNode = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/jvm.memory.max")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(2000))
                    .onErrorReturn(null)
                    .block();

            if (usedNode != null && maxNode != null) {
                double used = usedNode.get("measurements").get(0).get("value").asDouble(0.0);
                double max = maxNode.get("measurements").get(0).get("value").asDouble(1.0);
                if (max > 0) {
                    double percent = (used / max) * 100.0;
                    return Math.round(percent * 100.0) / 100.0;
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private HttpMetrics fetchHttpMetrics(String serviceName, String baseUrl) {
        HttpMetrics result = new HttpMetrics();
        try {
            JsonNode node = webClient.get()
                    .uri(baseUrl + "/actuator/metrics/http.server.requests")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(2000))
                    .onErrorReturn(null)
                    .block();

            if (node != null && node.has("measurements")) {
                long count = 0;
                double totalTimeSec = 0.0;

                for (JsonNode m : node.get("measurements")) {
                    String stat = m.get("statistic").asText("");
                    if ("COUNT".equalsIgnoreCase(stat)) {
                        count = m.get("value").asLong(0);
                    } else if ("TOTAL_TIME".equalsIgnoreCase(stat)) {
                        totalTimeSec = m.get("value").asDouble(0.0);
                    }
                }

                // Mean latency in ms
                if (count > 0) {
                    result.latencyMs = Math.round((totalTimeSec / count) * 1000.0 * 100.0) / 100.0;
                }

                // Request rate calculation
                long now = System.currentTimeMillis();
                Long prevCount = previousRequestCount.get(serviceName);
                Long prevTime = previousTimestampMillis.get(serviceName);

                if (prevCount != null && prevTime != null && now > prevTime) {
                    long deltaCount = count - prevCount;
                    if (deltaCount < 0) deltaCount = 0;
                    double deltaSec = (now - prevTime) / 1000.0;
                    result.requestRate = Math.round((deltaCount / deltaSec) * 100.0) / 100.0;
                } else {
                    result.requestRate = 0.0;
                }

                previousRequestCount.put(serviceName, count);
                previousTimestampMillis.put(serviceName, now);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private synchronized void appendCsvRecord(ServiceTelemetry telemetry) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvPath, true))) {
            writer.println(telemetry.toCsvRow());
        } catch (IOException e) {
            log.error("Failed to write telemetry record to CSV: {}", e.getMessage());
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
