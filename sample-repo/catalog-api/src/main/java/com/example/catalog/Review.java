package com.example.catalog;

public record Review(String productId, String author, int stars, int helpfulVotes, String text) {
}
