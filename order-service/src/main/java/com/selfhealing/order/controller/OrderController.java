package com.selfhealing.order.controller;

import com.selfhealing.order.model.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final Map<Long, Order> orderStore = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public OrderController() {
        // Seed initial orders for testing and demonstration
        long id1 = idCounter.getAndIncrement();
        orderStore.put(id1, new Order(id1, "Alice Smith", "Laptop", 1, 1200.00, "COMPLETED"));
        long id2 = idCounter.getAndIncrement();
        orderStore.put(id2, new Order(id2, "Bob Jones", "Wireless Mouse", 2, 25.50, "PROCESSING"));
        long id3 = idCounter.getAndIncrement();
        orderStore.put(id3, new Order(id3, "Charlie Brown", "Mechanical Keyboard", 1, 85.00, "CREATED"));
    }

    @GetMapping
    public List<Order> getAllOrders() {
        return new ArrayList<>(orderStore.values());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        Order order = orderStore.get(id);
        if (order != null) {
            return ResponseEntity.ok(order);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        long id = idCounter.getAndIncrement();
        order.setId(id);
        if (order.getStatus() == null || order.getStatus().isBlank()) {
            order.setStatus("CREATED");
        }
        orderStore.put(id, order);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }
}
