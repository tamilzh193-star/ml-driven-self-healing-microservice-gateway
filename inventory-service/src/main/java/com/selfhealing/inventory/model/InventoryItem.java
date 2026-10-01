package com.selfhealing.inventory.model;

public class InventoryItem {
    private Long id;
    private String productName;
    private Integer quantity;
    private Double price;
    private String location;

    public InventoryItem() {
    }

    public InventoryItem(Long id, String productName, Integer quantity, Double price, String location) {
        this.id = id;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
        this.location = location;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
