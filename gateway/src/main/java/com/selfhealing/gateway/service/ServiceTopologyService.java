package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.ServiceAnomalyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Novelty N4: Topology-Aware Root-Cause Localization.
 * Maintains service dependency DAG (Directed Acyclic Graph) and suppresses
 * redundant remediations on upstream caller services (e.g. order-service)
 * when a downstream dependency (e.g. payment-service or inventory-service)
 * is confirmed to be the root cause of cascading degradation.
 */
@Service
@ConfigurationProperties(prefix = "topology")
public class ServiceTopologyService {

    private static final Logger log = LoggerFactory.getLogger(ServiceTopologyService.class);

    /**
     * Map of upstream service -> list of downstream service dependencies.
     * e.g., "order-service" -> ["payment-service", "inventory-service"]
     */
    private Map<String, List<String>> dependencies = new ConcurrentHashMap<>();

    public ServiceTopologyService() {
        // Default built-in microservice topology:
        // order-service calls both payment-service and inventory-service
        List<String> orderDeps = new ArrayList<>(Arrays.asList("payment-service", "inventory-service"));
        dependencies.put("order-service", orderDeps);
        dependencies.put("payment-service", new ArrayList<>());
        dependencies.put("inventory-service", new ArrayList<>());
    }

    public Map<String, List<String>> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, List<String>> dependencies) {
        if (dependencies != null) {
            this.dependencies = new ConcurrentHashMap<>(dependencies);
        }
    }

    public List<String> getDownstreamDependencies(String serviceName) {
        return dependencies.getOrDefault(serviceName, Collections.emptyList());
    }

    /**
     * Identifies if serviceName is an upstream victim exhibiting symptoms caused
     * by an anomalous downstream dependency.
     *
     * @param serviceName The service candidate for remediation
     * @param state The candidate's current anomaly state
     * @param activeAnomalies Map of currently active service anomaly states
     * @return Optional containing root cause service name if suppression applies; empty otherwise.
     */
    public Optional<String> findDownstreamRootCause(
            String serviceName,
            ServiceAnomalyState state,
            Map<String, ServiceAnomalyState> activeAnomalies) {

        if (serviceName == null || activeAnomalies == null || activeAnomalies.isEmpty()) {
            return Optional.empty();
        }

        List<String> downstreams = getDownstreamDependencies(serviceName);
        if (downstreams.isEmpty()) {
            return Optional.empty();
        }

        // Attribution check: Upstream failure must be a symptom characteristic of downstream slowness/errors.
        // If the service has a local MEMORY_LEAK or CPU_SATURATION, it is an independent fault!
        String dominantFeature = state != null ? state.getDominantFeature() : null;
        String signature = state != null ? state.getFailureSignature() : null;

        boolean isExternalSymptom = dominantFeature == null
                || "latency".equalsIgnoreCase(dominantFeature)
                || "error_rate".equalsIgnoreCase(dominantFeature)
                || "LATENCY_SPIKE".equalsIgnoreCase(signature)
                || "ERROR_BURST".equalsIgnoreCase(signature)
                || "THROUGHPUT_SURGE".equalsIgnoreCase(signature);

        if (!isExternalSymptom) {
            log.info("[TOPOLOGY] Service '{}' has internal fault ({}/{}); not suppressing remediation.",
                    serviceName, dominantFeature, signature);
            return Optional.empty();
        }

        // Check if any downstream dependency has an active confirmed anomaly
        for (String downstream : downstreams) {
            ServiceAnomalyState dsState = activeAnomalies.get(downstream);
            if (dsState != null && dsState.isConfirmed()) {
                log.warn("[TOPOLOGY ROOT CAUSE] Service '{}' degradation suppressed! Downstream root cause '{}' is confirmed anomalous (score: {:.3f}, signature: {}).",
                        serviceName, downstream, dsState.getAverageAnomalyScore(), dsState.getFailureSignature());
                return Optional.of(downstream);
            }
        }

        return Optional.empty();
    }
}
