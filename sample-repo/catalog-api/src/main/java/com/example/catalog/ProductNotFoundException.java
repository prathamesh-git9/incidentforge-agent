package com.example.catalog;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productId) {
        super("product not found: " + productId);
    }
}
