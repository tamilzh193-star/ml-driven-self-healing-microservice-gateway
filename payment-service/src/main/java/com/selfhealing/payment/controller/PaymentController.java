package com.selfhealing.payment.controller;

import com.selfhealing.payment.model.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping({"/payments", "/payment"})
public class PaymentController {

    private final Map<Long, Payment> paymentStore = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public PaymentController() {
        // Seed sample payments
        long id1 = idCounter.getAndIncrement();
        paymentStore.put(id1, new Payment(id1, 1L, 1200.00, "CREDIT_CARD", "SUCCESS"));
        long id2 = idCounter.getAndIncrement();
        paymentStore.put(id2, new Payment(id2, 2L, 51.00, "DEBIT_CARD", "SUCCESS"));
    }

    @GetMapping({"", "/"})
    public List<Payment> getAllPayments() {
        return new ArrayList<>(paymentStore.values());
    }

    @GetMapping({"/{id}", "/{id}/"})
    public ResponseEntity<Payment> getPaymentById(@PathVariable Long id) {
        Payment payment = paymentStore.get(id);
        if (payment != null) {
            return ResponseEntity.ok(payment);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @PostMapping({"", "/"})
    public ResponseEntity<Payment> processPayment(@RequestBody Payment payment) {
        long id = idCounter.getAndIncrement();
        payment.setId(id);
        if (payment.getStatus() == null || payment.getStatus().isBlank()) {
            payment.setStatus("SUCCESS");
        }
        paymentStore.put(id, payment);
        return ResponseEntity.status(HttpStatus.CREATED).body(payment);
    }
}
