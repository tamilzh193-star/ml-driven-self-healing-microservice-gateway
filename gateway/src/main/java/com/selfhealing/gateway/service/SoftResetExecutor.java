package com.selfhealing.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Local development fallback restart executor.
 * Issues safe reset via /chaos/reset while explicitly recording SOFT_RESET mode
 * so no false Docker restart claims are made.
 */
@Component
public class SoftResetExecutor implements RestartExecutor {

    private static final Logger log = LoggerFactory.getLogger(SoftResetExecutor.class);

    private final WebClient webClient;

    public SoftResetExecutor() {
        this.webClient = WebClient.builder().build();
    }

    @Override
    public String getMode() {
        return "SOFT_RESET";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public RestartResult restart(String serviceName, String baseUrl) {
        long startTime = System.currentTimeMillis();
        log.info("[SOFT_RESET] Executing safe development reset for service '{}' at {}", serviceName, baseUrl);

        try {
            webClient.post()
                    .uri(baseUrl + "/chaos/reset")
                    .retrieve()
                    .toBodilessEntity()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMillis(5000));

            long duration = System.currentTimeMillis() - startTime;
            String msg = String.format("Local safe reset triggered via %s/chaos/reset in %dms", baseUrl, duration);
            log.info("[SOFT_RESET] {}", msg);
            return new RestartResult(true, "SOFT_RESET", msg, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String err = String.format("Failed to execute /chaos/reset on %s: %s", serviceName, e.getMessage());
            log.error("[SOFT_RESET] {}", err);
            return new RestartResult(false, "SOFT_RESET", err, duration);
        }
    }
}
