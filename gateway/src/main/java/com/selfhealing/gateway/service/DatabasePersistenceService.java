package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.MlPredictionResponse;
import com.selfhealing.gateway.model.HealingActionType;
import com.selfhealing.gateway.model.HealingAuditRecord;
import com.selfhealing.gateway.model.HealingStatus;
import com.selfhealing.gateway.model.ServiceTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Asynchronous relational persistence coordinator for telemetry, ML anomaly inferences,
 * and autonomous healing audit trails. Executes on Schedulers.boundedElastic() to prevent
 * blocking Spring Cloud Gateway's reactive Netty event loops.
 */
@Service
public class DatabasePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DatabasePersistenceService.class);

    @Value("${persistence.database.enabled:true}")
    private boolean persistenceEnabled = true;

    public void setPersistenceEnabled(boolean persistenceEnabled) {
        this.persistenceEnabled = persistenceEnabled;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    // In-memory fallback buffers in case database connectivity encounters temporary issues
    private final Deque<ServiceTelemetry> fallbackTelemetry = new ConcurrentLinkedDeque<>();
    private final Deque<HealingAuditRecord> fallbackHealingAudits = new ConcurrentLinkedDeque<>();

    public DatabasePersistenceService(@Autowired(required = false) JdbcTemplate jdbcTemplate,
                                      @Autowired(required = false) DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    /**
     * Persists a batch of telemetry records asynchronously.
     */
    public Mono<Void> persistTelemetryBatchAsync(List<ServiceTelemetry> telemetryList) {
        if (!persistenceEnabled || telemetryList == null || telemetryList.isEmpty() || jdbcTemplate == null) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO telemetry_records (timestamp, service, cpu, memory, latency, error_rate, request_rate, health_status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try {
                List<Object[]> batchArgs = new ArrayList<>();
                for (ServiceTelemetry t : telemetryList) {
                    batchArgs.add(new Object[]{
                            t.getTimestamp() != null ? t.getTimestamp() : Instant.now().toString(),
                            t.getServiceName(),
                            t.getCpu() != null ? t.getCpu() : 0.0,
                            t.getMemory() != null ? t.getMemory() : 0.0,
                            t.getLatency() != null ? t.getLatency() : 0.0,
                            t.getErrorRate() != null ? t.getErrorRate() : 0.0,
                            t.getRequestRate() != null ? t.getRequestRate() : 0.0,
                            t.getHealthStatus() != null ? t.getHealthStatus() : "UP"
                    });
                }
                jdbcTemplate.batchUpdate(sql, batchArgs);
                log.debug("Persisted batch of {} telemetry records to database", telemetryList.size());
            } catch (Exception e) {
                log.warn("Failed to persist telemetry batch to database: {}. Storing in memory fallback.", e.getMessage());
                fallbackTelemetry.addAll(telemetryList);
                while (fallbackTelemetry.size() > 500) {
                    fallbackTelemetry.pollFirst();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Persists an ML anomaly prediction record asynchronously.
     */
    public Mono<Void> persistAnomalyPredictionAsync(MlPredictionResponse prediction) {
        if (!persistenceEnabled || prediction == null || jdbcTemplate == null) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO anomaly_predictions (timestamp, service, prediction, is_anomaly, anomaly_score, cpu, memory, latency, error_rate, request_rate) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try {
                Map<String, Object> f = prediction.getFeatures() != null ? prediction.getFeatures() : Collections.emptyMap();
                jdbcTemplate.update(sql,
                        prediction.getTimestamp() != null ? prediction.getTimestamp() : Instant.now().toString(),
                        prediction.getService(),
                        prediction.getPrediction(),
                        Boolean.TRUE.equals(prediction.getIsAnomaly()),
                        prediction.getAnomalyScore() != null ? prediction.getAnomalyScore() : 0.0,
                        getDouble(f, "cpu"),
                        getDouble(f, "memory"),
                        getDouble(f, "latency"),
                        getDouble(f, "error_rate"),
                        getDouble(f, "request_rate")
                );
            } catch (Exception e) {
                log.warn("Failed to persist anomaly prediction for {}: {}", prediction.getService(), e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Persists or updates a healing audit record asynchronously.
     */
    public Mono<Void> persistHealingAuditAsync(HealingAuditRecord record) {
        if (!persistenceEnabled || record == null || jdbcTemplate == null) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO healing_audit_logs (id, service_name, action_type, trigger_reason, anomaly_score, " +
                    "attempt_count, action_status, recovery_status, recovery_details, cooldown_until, execution_duration_ms, timestamp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE action_status = VALUES(action_status), recovery_status = VALUES(recovery_status), " +
                    "recovery_details = VALUES(recovery_details)";
            try {
                jdbcTemplate.update(sql,
                        record.getId(),
                        record.getServiceName(),
                        record.getActionType() != null ? record.getActionType().name() : "NONE",
                        record.getTriggerReason(),
                        record.getAnomalyScore() != null ? record.getAnomalyScore() : 0.0,
                        record.getAttemptCount(),
                        record.getActionStatus() != null ? record.getActionStatus().name() : "UNKNOWN",
                        record.getRecoveryStatus() != null ? record.getRecoveryStatus() : "PENDING",
                        record.getRecoveryDetails(),
                        record.getCooldownUntil(),
                        record.getExecutionDurationMs() != null ? record.getExecutionDurationMs() : 0L,
                        record.getTimestamp() != null ? record.getTimestamp() : Instant.now().toString()
                );
                log.debug("Persisted healing audit record {} for service {}", record.getId(), record.getServiceName());
            } catch (Exception e) {
                // If ON DUPLICATE KEY syntax is not supported in ANSI H2, fall back to simple insert or update
                try {
                    String insertSql = "INSERT INTO healing_audit_logs (id, service_name, action_type, trigger_reason, anomaly_score, " +
                            "attempt_count, action_status, recovery_status, recovery_details, cooldown_until, execution_duration_ms, timestamp) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    jdbcTemplate.update(insertSql,
                            record.getId(),
                            record.getServiceName(),
                            record.getActionType() != null ? record.getActionType().name() : "NONE",
                            record.getTriggerReason(),
                            record.getAnomalyScore() != null ? record.getAnomalyScore() : 0.0,
                            record.getAttemptCount(),
                            record.getActionStatus() != null ? record.getActionStatus().name() : "UNKNOWN",
                            record.getRecoveryStatus() != null ? record.getRecoveryStatus() : "PENDING",
                            record.getRecoveryDetails(),
                            record.getCooldownUntil(),
                            record.getExecutionDurationMs() != null ? record.getExecutionDurationMs() : 0L,
                            record.getTimestamp() != null ? record.getTimestamp() : Instant.now().toString()
                    );
                } catch (Exception ex) {
                    log.warn("Failed to persist healing audit to database: {}. Buffering in memory fallback.", ex.getMessage());
                    fallbackHealingAudits.addFirst(record);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Query recent telemetry records from database.
     */
    public List<Map<String, Object>> getRecentTelemetry(String serviceName, int limit) {
        if (jdbcTemplate == null) return Collections.emptyList();
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        try {
            if (serviceName != null && !serviceName.trim().isEmpty()) {
                String sql = "SELECT * FROM telemetry_records WHERE service = ? ORDER BY id DESC LIMIT ?";
                return jdbcTemplate.queryForList(sql, serviceName, safeLimit);
            } else {
                String sql = "SELECT * FROM telemetry_records ORDER BY id DESC LIMIT ?";
                return jdbcTemplate.queryForList(sql, safeLimit);
            }
        } catch (Exception e) {
            log.warn("Database query for telemetry failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Query recent healing audit logs from database.
     */
    public List<Map<String, Object>> getRecentHealingAudits(int limit) {
        if (jdbcTemplate == null) return Collections.emptyList();
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        try {
            String sql = "SELECT * FROM healing_audit_logs ORDER BY created_at DESC LIMIT ?";
            return jdbcTemplate.queryForList(sql, safeLimit);
        } catch (Exception e) {
            log.warn("Database query for healing audits failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Query recent ML anomaly predictions from database.
     */
    public List<Map<String, Object>> getRecentAnomalies(int limit) {
        if (jdbcTemplate == null) return Collections.emptyList();
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        try {
            String sql = "SELECT * FROM anomaly_predictions WHERE is_anomaly = true ORDER BY id DESC LIMIT ?";
            return jdbcTemplate.queryForList(sql, safeLimit);
        } catch (Exception e) {
            log.warn("Database query for anomaly predictions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Inspect database connectivity, datasource metadata, and table row counts.
     */
    public Map<String, Object> getDatabaseStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("persistenceEnabled", persistenceEnabled);

        if (dataSource == null || jdbcTemplate == null) {
            status.put("databaseStatus", "UNCONFIGURED");
            status.put("fallbackActive", true);
            return status;
        }

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            status.put("databaseStatus", "CONNECTED");
            status.put("databaseProductName", meta.getDatabaseProductName());
            status.put("databaseProductVersion", meta.getDatabaseProductVersion());
            status.put("databaseUrl", meta.getURL());
            status.put("driverName", meta.getDriverName());

            // Query table row counts
            status.put("telemetryRowCount", queryCount("telemetry_records"));
            status.put("anomalyRowCount", queryCount("anomaly_predictions"));
            status.put("healingAuditRowCount", queryCount("healing_audit_logs"));
            status.put("fallbackActive", false);

        } catch (Exception e) {
            status.put("databaseStatus", "OFFLINE_FALLBACK");
            status.put("error", e.getMessage());
            status.put("fallbackActive", true);
            status.put("fallbackTelemetryRecords", fallbackTelemetry.size());
            status.put("fallbackHealingRecords", fallbackHealingAudits.size());
        }

        return status;
    }

    private long queryCount(String tableName) {
        try {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private double getDouble(Map<String, Object> map, String key) {
        if (map == null) return 0.0;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        } else if (val != null) {
            try {
                return Double.parseDouble(val.toString());
            } catch (Exception ignored) {
            }
        }
        return 0.0;
    }

    public Mono<Void> persistPolicyStatAsync(String contextKey, String actionType, double alpha, double beta, int successes, int failures) {
        if (!persistenceEnabled || jdbcTemplate == null) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            try {
                String sql = "INSERT INTO policy_stats (context_key, action_type, alpha, beta, successes, failures) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE alpha=?, beta=?, successes=?, failures=?, updated_at=CURRENT_TIMESTAMP";
                jdbcTemplate.update(sql,
                        contextKey, actionType, alpha, beta, successes, failures,
                        alpha, beta, successes, failures);
                log.debug("Persisted policy stats for {} - {}: alpha={}, beta={}", contextKey, actionType, alpha, beta);
            } catch (Exception e) {
                try {
                    String h2Sql = "MERGE INTO policy_stats (context_key, action_type, alpha, beta, successes, failures) " +
                            "KEY (context_key, action_type) VALUES (?, ?, ?, ?, ?, ?)";
                    jdbcTemplate.update(h2Sql, contextKey, actionType, alpha, beta, successes, failures);
                } catch (Exception ignored) {
                    log.debug("Failed to persist policy stats to db: {}", e.getMessage());
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Loads all persisted contextual Thompson sampling policy statistics from the database.
     * Returns an empty list safely if persistence is disabled, jdbcTemplate is null, or if table/db is unavailable.
     */
    public List<Map<String, Object>> loadAllPolicyStats() {
        if (!persistenceEnabled || jdbcTemplate == null) {
            return Collections.emptyList();
        }
        try {
            String sql = "SELECT context_key, action_type, alpha, beta, successes, failures FROM policy_stats";
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("Could not load policy_stats from database (table missing or offline): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public void resetAllTables() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.execute("DELETE FROM telemetry_records");
            jdbcTemplate.execute("DELETE FROM anomaly_predictions");
            jdbcTemplate.execute("DELETE FROM healing_audit_logs");
            try {
                jdbcTemplate.execute("DELETE FROM policy_stats");
            } catch (Exception ignored) {}
            fallbackTelemetry.clear();
            fallbackHealingAudits.clear();
            log.info("Reset all database tables successfully.");
        } catch (Exception e) {
            log.warn("Failed to reset tables: {}", e.getMessage());
        }
    }
}
