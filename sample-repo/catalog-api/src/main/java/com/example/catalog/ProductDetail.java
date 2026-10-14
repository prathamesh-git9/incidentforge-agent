package com.example.catalog;

import java.util.List;

/** Model for the product page. */
public record ProductDetail(Product product, long reviewCount, double averageStars, List<Review> topReviews) {
}
