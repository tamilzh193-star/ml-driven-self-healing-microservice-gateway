package com.selfhealing.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security unit tests verifying administrative and chaos endpoint protection,
 * authentication enforcement, key validation, and open access to business and ML routes.
 */
public class AdminAuthFilterTest {

    private AdminAuthFilter filter;
    private static final String VALID_KEY = "test-secret-admin-key-xyz";

    @BeforeEach
    void setUp() {
        filter = new AdminAuthFilter();
        filter.setAdminAuthEnabled(true);
        filter.setAdminApiKey(VALID_KEY);
    }

    private GatewayFilterChain createMockChain(AtomicBoolean chainExecuted) {
        return exchange -> {
            chainExecuted.set(true);
            return Mono.empty();
        };
    }

    @Test
    @DisplayName("Verify that missing X-Admin-Key on chaos endpoints returns 401 Unauthorized")
    void testMissingAdminKeyOnChaosEndpointReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/order-service/chaos/slow?delayMs=1600")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainExecuted = new AtomicBoolean(false);

        filter.filter(exchange, createMockChain(chainExecuted)).block();

        assertFalse(chainExecuted.get(), "FilterChain should NOT have executed for unauthenticated request");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(),
                "Missing admin key must return HTTP 401 Unauthorized");
    }

    @Test
    @DisplayName("Verify that invalid X-Admin-Key on chaos endpoints returns 403 Forbidden")
    void testIncorrectAdminKeyOnChaosEndpointReturns403() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/payment-service/chaos/error")
                .header("X-Admin-Key", "invalid-or-forged-key")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainExecuted = new AtomicBoolean(false);

        filter.filter(exchange, createMockChain(chainExecuted)).block();

        assertFalse(chainExecuted.get(), "FilterChain should NOT have executed for invalid admin key");
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(),
                "Invalid admin key must return HTTP 403 Forbidden");
    }

    @Test
    @DisplayName("Verify that correct X-Admin-Key allows administrative requests to proceed")
    void testCorrectAdminKeyAllowsRequest() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/inventory-service/chaos/reset")
                .header("X-Admin-Key", VALID_KEY)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainExecuted = new AtomicBoolean(false);

        filter.filter(exchange, createMockChain(chainExecuted)).block();

        assertTrue(chainExecuted.get(), "FilterChain should have executed for correctly authenticated request");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNotEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Verify that ordinary business routes remain accessible without an administrator key")
    void testOrdinaryBusinessRoutesAccessibleWithoutKey() {
        String[] businessPaths = {
                "/orders",
                "/orders/101",
                "/order-service/orders",
                "/payments",
                "/payments/process",
                "/inventory",
                "/inventory/check/item-42",
                "/",
                "/dashboard",
                "/api/dashboard/stats",
                "/api/v2/overview",
                "/gateway/health"
        };

        for (String path : businessPaths) {
            MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            AtomicBoolean chainExecuted = new AtomicBoolean(false);

            filter.filter(exchange, createMockChain(chainExecuted)).block();

            assertTrue(chainExecuted.get(), "Business route '" + path + "' must be accessible without admin key");
            assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
            assertNotEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    @DisplayName("Verify that ML prediction and telemetry read requests are not blocked by the filter")
    void testMlPredictionRoutesAccessibleWithoutKey() {
        String[] mlPaths = {
                "/gateway/ml/status",
                "/gateway/ml/latest",
                "/gateway/ml/history",
                "/gateway/ml/evaluate",
                "/gateway/telemetry/latest",
                "/gateway/anomaly/status",
                "/gateway/anomaly/confirmed",
                "/gateway/healing/status",
                "/gateway/healing/history",
                "/gateway/db/status",
                "/gateway/db/telemetry"
        };

        for (String path : mlPaths) {
            MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            AtomicBoolean chainExecuted = new AtomicBoolean(false);

            filter.filter(exchange, createMockChain(chainExecuted)).block();

            assertTrue(chainExecuted.get(), "ML/Telemetry route '" + path + "' must NOT be blocked by admin filter");
            assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
            assertNotEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    @DisplayName("Verify that administrative reset and manual healing trigger endpoints are strictly protected")
    void testAdministrativeAndChaosEndpointsProtected() {
        String[] adminPaths = {
                "/gateway/healing/trigger/order-service",
                "/gateway/healing/reset",
                "/gateway/anomaly/reset",
                "/gateway/anomaly/reset/payment-service",
                "/gateway/db/reset",
                "/api/dashboard/chaos/trigger",
                "/api/dashboard/reset",
                "/api/dashboard/healing/trigger",
                "/order-service/chaos/slow",
                "/payment-service/chaos/error",
                "/inventory-service/chaos/memory",
                "/admin/config"
        };

        for (String path : adminPaths) {
            // 1. Missing key -> Rejected with 401
            MockServerHttpRequest unauthReq = MockServerHttpRequest.post(path).build();
            MockServerWebExchange unauthExchange = MockServerWebExchange.from(unauthReq);
            AtomicBoolean unauthExecuted = new AtomicBoolean(false);

            filter.filter(unauthExchange, createMockChain(unauthExecuted)).block();
            assertFalse(unauthExecuted.get(), "Admin route '" + path + "' must reject unauthenticated requests");
            assertEquals(HttpStatus.UNAUTHORIZED, unauthExchange.getResponse().getStatusCode());

            // 2. Correct key -> Allowed
            MockServerHttpRequest authReq = MockServerHttpRequest.post(path)
                    .header("X-Admin-Key", VALID_KEY)
                    .build();
            MockServerWebExchange authExchange = MockServerWebExchange.from(authReq);
            AtomicBoolean authExecuted = new AtomicBoolean(false);

            filter.filter(authExchange, createMockChain(authExecuted)).block();
            assertTrue(authExecuted.get(), "Admin route '" + path + "' must allow authenticated requests with valid key");
        }
    }

    @Test
    @DisplayName("Verify that disabled security mode permits operations without requiring admin key")
    void testDisabledAuthPermitsRequests() {
        filter.setAdminAuthEnabled(false);

        MockServerHttpRequest request = MockServerHttpRequest.post("/order-service/chaos/slow").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainExecuted = new AtomicBoolean(false);

        filter.filter(exchange, createMockChain(chainExecuted)).block();

        assertTrue(chainExecuted.get(), "When admin auth is disabled, chaos requests should proceed");
    }

    @Test
    @DisplayName("Verify that when admin auth is enabled but no server key is configured, requests fail safely with 403")
    void testUnconfiguredAdminKeyFailsSafely() {
        filter.setAdminApiKey(""); // Unconfigured server key

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/order-service/chaos/slow")
                .header("X-Admin-Key", "any-key")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainExecuted = new AtomicBoolean(false);

        filter.filter(exchange, createMockChain(chainExecuted)).block();

        assertFalse(chainExecuted.get(), "FilterChain should NOT have executed when server key is unconfigured");
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }
}
