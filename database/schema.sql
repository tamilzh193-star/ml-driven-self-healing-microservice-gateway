-- =====================================================================
-- Self-Healing Cloud Microservice Gateway
-- Phase 13: Database Schema & Relational DDL (MySQL)
-- =====================================================================

CREATE DATABASE IF NOT EXISTS self_healing_db;
USE self_healing_db;

-- ---------------------------------------------------------------------
-- 1. Table: telemetry_records
-- Continuous time-series telemetry observed from Spring Boot Actuators
-- ---------------------------------------------------------------------
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
    INDEX idx_service_created (service, created_at),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 2. Table: anomaly_predictions
-- Machine Learning (Isolation Forest) inference classifications and scores
-- ---------------------------------------------------------------------
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
    INDEX idx_service_anomaly (service, is_anomaly),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 3. Table: healing_audit_logs
-- Immutable audit trail of autonomous self-healing remediation actions
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS healing_audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    service_name VARCHAR(50) NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    trigger_reason TEXT,
    anomaly_score DOUBLE DEFAULT 0.0,
    attempt_count INT DEFAULT 1,
    action_status VARCHAR(30) NOT NULL,
    recovery_status VARCHAR(30) DEFAULT 'PENDING',
    recovery_details TEXT,
    cooldown_until VARCHAR(50),
    execution_duration_ms BIGINT DEFAULT 0,
    timestamp VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_service_action (service_name, action_type),
    INDEX idx_recovery_status (recovery_status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
