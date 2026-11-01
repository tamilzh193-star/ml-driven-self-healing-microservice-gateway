# Dashboard Failure Root-Cause Analysis & Fix Notes

## 1. Executive Summary
During the architectural review of the Self-Healing Cloud Gateway dashboard (`gateway/src/main/resources/templates/dashboard.html` and `DashboardController.java`), several structural deficiencies were identified that caused client-side rendering failures, monolithic coupling, and high backend polling overhead.

This document details the root causes identified, their operational impacts, and the architectural remediations implemented in the V2 multi-view dashboard.

---

## 2. Identified Root Causes

### Root Cause 1: Monolithic Polling Callback with No Error Boundaries
- **Mechanism**: The legacy dashboard executed a single `pollDashboard()` function every 3 seconds:
  ```javascript
  fetch('/api/dashboard/stats')
    .then(res => res.json())
    .then(data => {
        // Updated KPIs, DB, ML, Services, and Audit Trail in one single callback
    });
  ```
- **Failure Mode**: If any single data field was missing, null, or malformed, or if any DOM element ID failed to resolve, the entire JavaScript execution halted due to an unhandled exception. This blanked out or froze every panel on the page.
- **Remediation**: 
  - Decompose dashboard into decoupled, independently loading component modules (`OverviewModule`, `ServicesModule`, `AnomalyModule`, `IncidentsModule`, `PolicyModule`, `ExperimentsModule`).
  - Implement per-component error boundaries with inline retry buttons and skeleton loading states. A failure in the audit log or database panel will never prevent the real-time telemetry or service health panels from updating.

### Root Cause 2: Heavy Aggregation on Every Poll Cycle
- **Mechanism**: `GET /api/dashboard/stats` invoked `buildDashboardData()` on every 3-second request. This method synchronously aggregated:
  - Latest telemetry from 3 microservices
  - All anomaly confirmation states
  - External ML service health probe via HTTP (`checkMlHealth()`)
  - Full database status query via JDBC
  - Active healing cooldowns and full audit history reversal
- **Failure Mode**: When the ML service or database experienced transient latency or connection saturation, `buildDashboardData()` exceeded the client fetch timeout (3–5s), causing cascading ReadTimeout errors on the browser.
- **Remediation**:
  - Introduce **Server-Sent Events (SSE)** via `GET /api/v2/stream` that pushes event updates every 2 seconds only when states change or telemetry arrives.
  - Implement client-side automatic fallback to decoupled 3-second polling only if the SSE stream disconnects.
  - Cache slow infrastructure metrics (such as model parameter metadata) so live telemetry polling does not trigger external REST health checks.

### Root Cause 3: Direct Unauthenticated Chaos Endpoints
- **Mechanism**: The UI buttons directly invoked `/api/dashboard/chaos/trigger` and `/api/dashboard/reset` without authentication or authorization. Furthermore, the gateway exposed `/order-service/**`, `/payment-service/**`, and `/inventory-service/**` direct routes with `StripPrefix=1`, which allowed external clients to trigger `/order-service/chaos/error` directly through the gateway.
- **Failure Mode**: Security exposure; unauthorized clients or web crawlers could accidentally trigger system-wide crashes or database resets.
- **Remediation**:
  - Secure all chaos, healing trigger, and administrative reset endpoints with an `X-Admin-Key` header requirement validated against `ADMIN_API_KEY`.
  - Add a gateway routing filter that blocks any route containing `/chaos/` unless the valid admin key is present.

### Root Cause 4: Monolithic Single-File Template Maintenance
- **Mechanism**: `dashboard.html` was an 800+ line monolithic file containing HTML structure, inline CSS, and inline JavaScript logic mixed together.
- **Failure Mode**: Any enhancement or fix risked breaking unrelated sections, and offline vendor libraries could not be cleanly packaged.
- **Remediation**:
  - Transform `templates/dashboard.html` into a thin shell template.
  - Extract styles into modular CSS variables and layout files (`static/css/dashboard.css`).
  - Extract client logic into vanilla ES modules (`static/js/app.js`, `static/js/api.js`, `static/js/components/`).
  - Vendor chart libraries locally into `static/vendor/` with zero runtime CDN requirements.

---

## 3. Verification & Acceptance Criteria
1. Each panel (KPIs, Service Health, Real-Time Telemetry, Anomaly Engine, Incidents, Policy, Experiments) renders independently.
2. If any single endpoint returns HTTP 500 or times out, an inline warning card is displayed with a "Retry" button while other panels remain fully live.
3. Live SSE streaming operates smoothly; falling back to 3-second polling if SSE drops.
4. All fault injection and manual healing actions prompt for the admin API key and display confirmation dialogs before execution.
