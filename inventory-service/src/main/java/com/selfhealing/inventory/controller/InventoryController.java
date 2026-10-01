package com.selfhealing.inventory.controller;

import com.selfhealing.inventory.model.InventoryItem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final Map<Long, InventoryItem> inventoryStore = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public InventoryController() {
        // Seed sample inventory items
        long id1 = idCounter.getAndIncrement();
        inventoryStore.put(id1, new InventoryItem(id1, "Laptop", 50, 1200.00, "Warehouse-A"));
        long id2 = idCounter.getAndIncrement();
        inventoryStore.put(id2, new InventoryItem(id2, "Wireless Mouse", 200, 25.50, "Warehouse-B"));
        long id3 = idCounter.getAndIncrement();
        inventoryStore.put(id3, new InventoryItem(id3, "Mechanical Keyboard", 100, 85.00, "Warehouse-A"));
    }

    @GetMapping
    public List<InventoryItem> getAllInventory() {
        return new ArrayList<>(inventoryStore.values());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InventoryItem> getInventoryById(@PathVariable Long id) {
        InventoryItem item = inventoryStore.get(id);
        if (item != null) {
            return ResponseEntity.ok(item);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @PostMapping("/update")
    public ResponseEntity<?> updateInventory(@RequestBody InventoryItem updateRequest) {
        if (updateRequest.getId() == null) {
            return ResponseEntity.badRequest().body("Item ID must be provided");
        }
        InventoryItem existingItem = inventoryStore.get(updateRequest.getId());
        if (existingItem == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Item not found");
        }
        if (updateRequest.getQuantity() != null) {
            existingItem.setQuantity(updateRequest.getQuantity());
        }
        if (updateRequest.getPrice() != null) {
            existingItem.setPrice(updateRequest.getPrice());
        }
        if (updateRequest.getLocation() != null && !updateRequest.getLocation().isBlank()) {
            existingItem.setLocation(updateRequest.getLocation());
        }
        return ResponseEntity.ok(existingItem);
    }
}
