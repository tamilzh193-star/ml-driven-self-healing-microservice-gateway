package com.selfhealing.order.controller;

import com.selfhealing.order.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping({"/orders", "/order"})
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final Map<Long, Order> orderStore = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    @Value("${payment.service.url:http://localhost:8082}")
    private String paymentServiceUrl;

    @Value("${inventory.service.url:http://localhost:8083}")
    private String inventoryServiceUrl;

    @Value("${order.downstream.propagation.enabled:true}")
    private boolean downstreamPropagationEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public OrderController() {
        // Seed initial orders for testing and demonstration
        long id1 = idCounter.getAndIncrement();
        orderStore.put(id1, new Order(id1, "Alice Smith", "Laptop", 1, 1200.00, "COMPLETED"));
        long id2 = idCounter.getAndIncrement();
        orderStore.put(id2, new Order(id2, "Bob Jones", "Wireless Mouse", 2, 25.50, "PROCESSING"));
        long id3 = idCounter.getAndIncrement();
        orderStore.put(id3, new Order(id3, "Charlie Brown", "Mechanical Keyboard", 1, 85.00, "CREATED"));
    }

    @GetMapping({"", "/"})
    public List<Order> getAllOrders() {
        return new ArrayList<>(orderStore.values());
    }

    @GetMapping({"/{id}", "/{id}/"})
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        Order order = orderStore.get(id);
        if (order != null) {
            return ResponseEntity.ok(order);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @PostMapping({"", "/"})
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        long id = idCounter.getAndIncrement();
        order.setId(id);
        if (order.getStatus() == null || order.getStatus().isBlank()) {
            order.setStatus("CREATED");
        }
        orderStore.put(id, order);

        if (downstreamPropagationEnabled) {
            callDownstreamServices(order);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    private void callDownstreamServices(Order order) {
        // 1. Check/reserve inventory item from inventory-service
        try {
            HttpRequest invRequest = HttpRequest.newBuilder()
                    .uri(URI.create(inventoryServiceUrl + "/inventory"))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> invResponse = httpClient.send(invRequest, HttpResponse.BodyHandlers.ofString());
            log.debug("[DOWNSTREAM] Inventory service response code: {}", invResponse.statusCode());
        } catch (Exception e) {
            log.debug("[DOWNSTREAM] Inventory service call skipped or timed out: {}", e.getMessage());
        }

        // 2. Process payment via payment-service
        try {
            String paymentJson = String.format("{\"orderId\":%d,\"amount\":%.2f,\"paymentMethod\":\"CREDIT_CARD\"}",
                    order.getId(), order.getPrice() != null ? order.getPrice() : 100.0);
            HttpRequest payRequest = HttpRequest.newBuilder()
                    .uri(URI.create(paymentServiceUrl + "/payments"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(paymentJson))
                    .build();
            HttpResponse<String> payResponse = httpClient.send(payRequest, HttpResponse.BodyHandlers.ofString());
            log.debug("[DOWNSTREAM] Payment service response code: {}", payResponse.statusCode());
        } catch (Exception e) {
            log.debug("[DOWNSTREAM] Payment service call skipped or timed out: {}", e.getMessage());
        }
    }
}

