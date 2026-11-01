-- =====================================================================
-- Self-Healing Cloud Microservice Gateway - MySQL 8 Initialization DDL
-- =====================================================================

CREATE DATABASE IF NOT EXISTS self_healing_db;
USE self_healing_db;

-- 1. Real-time multivariate telemetry records
CREATE TABLE IF NOT EXISTS telemetry_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timestamp VARCHAR(50) NOT NULL,
    service VARCHAR(50) NOT NULL,
    cpu DOUBLE DEFAULT 0.0,
    memory DOUBLE DEFAULT 0.0,
    latency DOUBLE DEFAULT 0.0,
    error_rate DOUBLE DEFAULT 0.0,
    request_rate DOUBLE DEFAULT 0.0,
    health_status VARCHAR(20) DEFAULT 'UP',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tel_service (service),
    INDEX idx_tel_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. Isolation Forest ML anomaly predictions
CREATE TABLE IF NOT EXISTS anomaly_predictions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timestamp VARCHAR(50) NOT NULL,
    service VARCHAR(50) NOT NULL,
    prediction VARCHAR(20) NOT NULL,
    is_anomaly BOOLEAN DEFAULT FALSE,
    anomaly_score DOUBLE DEFAULT 0.0,
    cpu DOUBLE DEFAULT 0.0,
    memory DOUBLE DEFAULT 0.0,
    latency DOUBLE DEFAULT 0.0,
    error_rate DOUBLE DEFAULT 0.0,
    request_rate DOUBLE DEFAULT 0.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ano_service (service),
    INDEX idx_ano_is_anomaly (is_anomaly)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Autonomous healing immutable audit logs
CREATE TABLE IF NOT EXISTS healing_audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    service_name VARCHAR(50) NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    trigger_reason VARCHAR(1000),
    anomaly_score DOUBLE DEFAULT 0.0,
    attempt_count INT DEFAULT 1,
    action_status VARCHAR(30) NOT NULL,
    recovery_status VARCHAR(30) DEFAULT 'PENDING',
    recovery_details VARCHAR(1000),
    cooldown_until VARCHAR(50),
    execution_duration_ms BIGINT DEFAULT 0,
    timestamp VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_heal_service (service_name),
    INDEX idx_heal_action (action_type),
    INDEX idx_heal_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 4. Table: policy_stats
-- Contextual Thompson Sampling policy posteriors (Beta distribution)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_stats (
    context_key VARCHAR(100) NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    alpha DOUBLE DEFAULT 1.0,
    beta DOUBLE DEFAULT 1.0,
    successes INT DEFAULT 0,
    failures INT DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (context_key, action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
