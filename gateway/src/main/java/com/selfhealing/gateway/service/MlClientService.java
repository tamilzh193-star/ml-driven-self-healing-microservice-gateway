package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionRequest;
import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.ServiceTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Service
public class MlClientService {

    private static final Logger log = LoggerFactory.getLogger(MlClientService.class);
    private static final int MAX_HISTORY = 100;

    private final WebClient webClient;

    @Value("${ml.service.url:http://localhost:5000}")
    private String mlServiceUrl;

    @Value("${ml.service.enabled:true}")
    private boolean mlEnabled;

    @Value("${ml.service.timeout-ms:3000}")
    private long timeoutMs;

    // Cache of latest prediction per service
    private final Map<String, MlPredictionResponse> latestPredictions = new ConcurrentHashMap<>();

    // Rolling history of recent evaluations
    private final Deque<MlPredictionResponse> history = new ConcurrentLinkedDeque<>();

    @Autowired(required = false)
    private AnomalyConfirmationService anomalyConfirmationService;

    public MlClientService() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * Checks health and connectivity of the Python Flask ML Service.
     */
    public Map<String, Object> checkMlHealth() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mlServiceUrl", mlServiceUrl);
        status.put("enabled", mlEnabled);

        if (!mlEnabled) {
            status.put("status", "DISABLED");
            return status;
        }

        try {
            long start = System.currentTimeMillis();
            Map healthResp = webClient.get()
                    .uri(mlServiceUrl + "/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(timeoutMs));

            long duration = System.currentTimeMillis() - start;
            status.put("status", "UP");
            status.put("health", healthResp);
            status.put("pingLatencyMs", duration);

            try {
                Map modelInfo = webClient.get()
                        .uri(mlServiceUrl + "/model/info")
                        .retrieve()
                        .bodyToMono(Map.class)
                        .subscribeOn(Schedulers.boundedElastic())
                        .block(Duration.ofMillis(timeoutMs));
                status.put("modelInfo", modelInfo);
            } catch (Exception e) {
                status.put("modelInfo", "Failed to retrieve model info: " + e.getMessage());
            }

        } catch (Exception e) {
            log.warn("ML Service at {} is unreachable: {}", mlServiceUrl, e.getMessage());
            status.put("status", "DOWN");
            status.put("error", e.getMessage());
        }

        return status;
    }

    /**
     * Dispatches a batch of telemetry observations to the Flask ML Service for anomaly inference.
     */
    public List<MlPredictionResponse> evaluateBatch(List<ServiceTelemetry> telemetryList) {
        if (!mlEnabled || telemetryList == null || telemetryList.isEmpty()) {
            return Collections.emptyList();
        }

        List<MlPredictionRequest> requests = telemetryList.stream()
                .map(MlPredictionRequest::from)
                .collect(Collectors.toList());

        List<MlPredictionResponse> responses = Collections.emptyList();

        try {
            responses = webClient.post()
                    .uri(mlServiceUrl + "/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requests)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<MlPredictionResponse>>() {})
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(timeoutMs));

        } catch (Exception e) {
            log.warn("Failed to dispatch telemetry batch to ML service ({}): {}", mlServiceUrl, e.getMessage());
            // Create fallback responses so tracking is not lost
            responses = telemetryList.stream()
                    .map(t -> MlPredictionResponse.unavailable(t.getServiceName(), "ML Service unreachable: " + e.getMessage()))
                    .collect(Collectors.toList());
        }

        if (responses != null) {
            for (MlPredictionResponse res : responses) {
                if (res.getService() != null) {
                    latestPredictions.put(res.getService(), res);
                    history.addLast(res);
                    while (history.size() > MAX_HISTORY) {
                        history.pollFirst();
                    }

                    if (anomalyConfirmationService != null) {
                        try {
                            anomalyConfirmationService.recordEvaluation(res);
                        } catch (Exception e) {
                            log.error("Failed to update anomaly confirmation for {}: {}", res.getService(), e.getMessage());
                        }
                    }

                    if (Boolean.TRUE.equals(res.getIsAnomaly())) {
                        log.warn("[ML ANOMALY] Service '{}' flagged as ANOMALY! Anomaly score: {}, features: {}",
                                res.getService(), res.getAnomalyScore(), res.getFeatures());
                    } else if ("NORMAL".equalsIgnoreCase(res.getPrediction())) {
                        log.info("[ML NORMAL] Service '{}' evaluated as NORMAL. Score: {}",
                                res.getService(), res.getAnomalyScore());
                    }
                }
            }
        }

        return responses;
    }

    /**
     * Returns the latest anomaly prediction per registered service.
     */
    public Map<String, MlPredictionResponse> getLatestPredictions() {
        return new LinkedHashMap<>(latestPredictions);
    }

    /**
     * Returns recent rolling history of ML predictions.
     */
    public List<MlPredictionResponse> getHistory() {
        return new ArrayList<>(history);
    }
}
