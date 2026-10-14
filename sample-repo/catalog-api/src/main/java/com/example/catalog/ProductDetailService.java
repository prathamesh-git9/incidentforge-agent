package com.example.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds the product page model. */
public final class ProductDetailService {

    private static final int TOP_REVIEWS = 3;

    private final ProductRepository products;
    private final ReviewRepository reviews;

    public ProductDetailService(ProductRepository products, ReviewRepository reviews) {
        this.products = Objects.requireNonNull(products);
        this.reviews = Objects.requireNonNull(reviews);
    }

    public ProductDetail detail(String productId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        // 2.4.0: review summary and the most helpful reviews on the product page
        List<Review> all = reviews.findByProductId(productId);
        double average = all.stream().mapToInt(Review::stars).average().orElse(0.0);
        List<Review> top = all.stream()
                .sorted(Comparator.comparingInt(Review::helpfulVotes).reversed())
                .limit(TOP_REVIEWS)
                .toList();
        return new ProductDetail(product, all.size(), average, top);
    }
}
