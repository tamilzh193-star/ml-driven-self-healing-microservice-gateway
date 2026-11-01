package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.HealingAuditRecord;
import com.selfhealing.gateway.model.ServiceAnomalyState;
import com.selfhealing.gateway.model.ServiceTelemetry;
import com.selfhealing.gateway.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.result.view.Rendering;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final TelemetryCollectorService telemetryService;
    private final AnomalyConfirmationService anomalyService;
    private final HealingEngineService healingService;
    private final RemediationExecutionService remediationService;
    private final MlClientService mlClientService;
    private final WebClient webClient;

    @Autowired(required = false)
    private DatabasePersistenceService databaseService;

    private static final Map<String, Integer> SERVICE_PORTS = Map.of(
            "order-service", 8081,
            "payment-service", 8082,
            "inventory-service", 8083
    );

    @Value("${order.service.url:http://localhost:8081}")
    private String orderServiceUrl;

    @Value("${payment.service.url:http://localhost:8082}")
    private String paymentServiceUrl;

    @Value("${inventory.service.url:http://localhost:8083}")
    private String inventoryServiceUrl;

    private String getServiceBaseUrl(String service) {
        if ("order-service".equalsIgnoreCase(service)) return orderServiceUrl;
        if ("payment-service".equalsIgnoreCase(service)) return paymentServiceUrl;
        if ("inventory-service".equalsIgnoreCase(service)) return inventoryServiceUrl;
        return "http://localhost:" + SERVICE_PORTS.getOrDefault(service, 8081);
    }

    public DashboardController(TelemetryCollectorService telemetryService,
                               AnomalyConfirmationService anomalyService,
                               HealingEngineService healingService,
                               RemediationExecutionService remediationService,
                               MlClientService mlClientService) {
        this.telemetryService = telemetryService;
        this.anomalyService = anomalyService;
        this.healingService = healingService;
        this.remediationService = remediationService;
        this.mlClientService = mlClientService;
        this.webClient = WebClient.builder().build();
    }

    /**
     * Main Dashboard Page View.
     */
    @GetMapping({"/", "/dashboard"})
    public Mono<Rendering> renderDashboard() {
        return buildDashboardData().map(data ->
                Rendering.view("dashboard")
                        .modelAttribute("data", data)
                        .build()
        );
    }

    /**
     * Dedicated Project Information & System Overview Page View.
     */
    @GetMapping({"/info", "/project-info"})
    public Mono<Rendering> renderProjectInfo() {
        return buildDashboardData().map(data ->
                Rendering.view("project-info")
                        .modelAttribute("data", data)
                        .build()
        );
    }

    /**
     * Live JSON API endpoint polled by dashboard JavaScript every 3 seconds.
     */
    @GetMapping("/api/dashboard/stats")
    @ResponseBody
    public Mono<Map<String, Object>> getDashboardStats() {
        return buildDashboardData();
    }

    /**
     * Interactive Chaos Injection Trigger from the UI.
     */
    @PostMapping("/api/dashboard/chaos/trigger")
    @ResponseBody
    public Mono<Map<String, Object>> triggerChaos(@RequestParam String service,
                                                  @RequestParam String type,
                                                  @RequestParam(required = false, defaultValue = "1600") String value) {
        Integer port = SERVICE_PORTS.get(service);
        if (port == null) {
            return Mono.just(Map.of("status", "ERROR", "message", "Unknown service: " + service));
        }

        String base = getServiceBaseUrl(service);
        String targetUrl;
        if ("slow".equalsIgnoreCase(type)) {
            targetUrl = base + "/chaos/slow?delayMs=" + value;
        } else if ("error".equalsIgnoreCase(type)) {
            targetUrl = base + "/chaos/error?active=true";
        } else if ("memory".equalsIgnoreCase(type)) {
            targetUrl = base + "/chaos/memory?megabytes=" + value;
        } else if ("cpu".equalsIgnoreCase(type)) {
            targetUrl = base + "/chaos/cpu?durationSec=" + value;
        } else if ("reset".equalsIgnoreCase(type)) {
            targetUrl = base + "/chaos/reset";
        } else {
            return Mono.just(Map.of("status", "ERROR", "message", "Unknown chaos type: " + type));
        }

        return webClient.post()
                .uri(targetUrl)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(4))
                .map(resp -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "SUCCESS");
                    result.put("service", service);
                    result.put("type", type);
                    result.put("chaosResponse", resp);
                    return result;
                })
                .onErrorResume(e -> Mono.just(Map.of(
                        "status", "ERROR",
                        "service", service,
                        "error", e.getMessage() != null ? e.getMessage() : "Failed to trigger chaos"
                )));
    }

    /**
     * Interactive Healing Trigger from the UI.
     */
    @PostMapping("/api/dashboard/healing/trigger")
    @ResponseBody
    public Mono<Map<String, Object>> triggerHealing(@RequestParam String service) {
        return Mono.fromCallable(() -> {
            ServiceAnomalyState state = anomalyService.getServiceState(service);
            HealingAuditRecord record = healingService.healService(state, true);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "SUCCESS");
            res.put("service", service);
            res.put("record", record);
            return res;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Administrative Full System Reset from the UI.
     */
    @PostMapping("/api/dashboard/reset")
    @ResponseBody
    public Mono<Map<String, Object>> resetSystem() {
        return Mono.fromCallable(() -> {
            // 1. Reset chaos in all microservices
            for (String sName : SERVICE_PORTS.keySet()) {
                try {
                    webClient.post().uri(getServiceBaseUrl(sName) + "/chaos/reset")
                            .retrieve().toBodilessEntity().block(Duration.ofSeconds(2));
                } catch (Exception ignored) {
                }
            }
            // 2. Reset anomaly confirmation states
            anomalyService.resetAll();
            // 3. Reset healing engine states
            healingService.resetEngine();
            // 4. Reset database tables
            if (databaseService != null) {
                databaseService.resetAllTables();
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "SUCCESS");
            res.put("message", "All microservice chaos, anomaly states, healing engine, and database tables reset cleanly.");
            return res;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Constructs the comprehensive real-time dashboard data payload.
     */
    private Mono<Map<String, Object>> buildDashboardData() {
        return Mono.fromCallable(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("timestamp", Instant.now().toString());

            // 1. Monitored Microservice Statuses
            Map<String, ServiceTelemetry> latestTelemetry = telemetryService.getLatestTelemetry();
            Map<String, ServiceAnomalyState> anomalyStates = anomalyService.getAllStates();

            List<Map<String, Object>> serviceCards = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : SERVICE_PORTS.entrySet()) {
                String sName = entry.getKey();
                int port = entry.getValue();

                ServiceTelemetry tel = latestTelemetry.get(sName);
                ServiceAnomalyState ano = anomalyStates.get(sName);

                Map<String, Object> sc = new LinkedHashMap<>();
                sc.put("name", sName);
                sc.put("port", port);
                sc.put("health", tel != null ? tel.getHealthStatus() : "UNKNOWN");
                sc.put("anomalyStatus", ano != null ? ano.getStatus().name() : "HEALTHY");
                sc.put("windowScore", ano != null ? (ano.getAnomaliesInWindow() + "/" + ano.getWindowSize()) : "0/5");
                sc.put("cpu", tel != null ? tel.getCpu() : 0.0);
                sc.put("memory", tel != null ? tel.getMemory() : 0.0);
                sc.put("latency", tel != null ? tel.getLatency() : 0.0);
                sc.put("errorRate", tel != null ? tel.getErrorRate() : 0.0);
                sc.put("requestRate", tel != null ? tel.getRequestRate() : 0.0);

                serviceCards.add(sc);
            }
            data.put("services", serviceCards);

            // 2. Gateway and Infrastructure nodes
            List<Map<String, Object>> infraNodes = new ArrayList<>();
            infraNodes.add(Map.of("name", "api-gateway", "port", 8080, "type", "Routing & Healing", "status", "UP"));
            infraNodes.add(Map.of("name", "ml-service", "port", 5000, "type", "Isolation Forest ML", "status", "UP"));

            Map<String, Object> dbStatus = databaseService != null ? databaseService.getDatabaseStatus() : Map.of("databaseStatus", "OFFLINE");
            infraNodes.add(Map.of(
                    "name", "relational-db",
                    "port", 3306,
                    "type", dbStatus.getOrDefault("databaseProductName", "H2 (MySQL Mode)").toString(),
                    "status", dbStatus.getOrDefault("databaseStatus", "OFFLINE").toString()
            ));
            data.put("infrastructure", infraNodes);

            // 3. ML Service Information
            Map<String, Object> mlInfo = new LinkedHashMap<>();
            try {
                mlInfo.putAll(mlClientService.checkMlHealth());
            } catch (Exception e) {
                mlInfo.put("status", "UNREACHABLE");
            }
            mlInfo.put("model", "Isolation Forest (scikit-learn)");
            mlInfo.put("contamination", 0.04);
            mlInfo.put("estimators", 100);
            mlInfo.put("decisionThreshold", -0.5140227742629923);
            mlInfo.put("heldOutAccuracy", 0.9448);
            mlInfo.put("heldOutPrecision", 0.7593);
            mlInfo.put("heldOutRecall", 0.9111);
            mlInfo.put("heldOutF1", 0.8283);
            mlInfo.put("heldOutFpr", 0.0494);
            mlInfo.put("heldOutSamples", 308);
            data.put("mlService", mlInfo);

            // 4. Healing Engine Status
            Map<String, Object> healingEngine = new LinkedHashMap<>();
            healingEngine.put("activeRedirections", remediationService.getActiveRedirections());
            healingEngine.put("activeRateLimits", remediationService.getActiveRateLimits());

            Map<String, String> activeCooldowns = new LinkedHashMap<>();
            Instant now = Instant.now();
            healingService.getCooldowns().forEach((s, exp) -> {
                if (exp != null && now.isBefore(exp)) {
                    long remainingSec = Duration.between(now, exp).toSeconds();
                    activeCooldowns.put(s, remainingSec + "s remaining");
                }
            });
            healingEngine.put("activeCooldowns", activeCooldowns);
            healingEngine.put("retryCounts", healingService.getRetryCounts());
            healingEngine.put("totalRemediations", healingService.getAuditHistory().size());

            // Recent 10 audit records
            List<HealingAuditRecord> fullHistory = healingService.getAuditHistory();
            List<HealingAuditRecord> recentAudit = new ArrayList<>(fullHistory);
            Collections.reverse(recentAudit);
            if (recentAudit.size() > 10) {
                recentAudit = recentAudit.subList(0, 10);
            }
            healingEngine.put("recentAuditHistory", recentAudit);
            data.put("healingEngine", healingEngine);

            // 5. Database Metrics
            data.put("database", dbStatus);

            // 6. Aggregate KPIs
            int totalIncidents = fullHistory.size();
            long recoveredCount = fullHistory.stream()
                    .filter(r -> "RECOVERED".equalsIgnoreCase(r.getRecoveryStatus()))
                    .count();
            double successRate = totalIncidents > 0 ? (recoveredCount * 100.0 / totalIncidents) : 100.0;

            Map<String, Object> kpis = new LinkedHashMap<>();
            kpis.put("totalRemediations", totalIncidents);
            kpis.put("successfulRecoveries", recoveredCount);
            kpis.put("healingSuccessRate", Math.round(successRate * 10.0) / 10.0);
            kpis.put("mttdTarget", "< 25.0s");
            kpis.put("mttrTarget", "< 15.0s");
            data.put("kpis", kpis);

            return data;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
