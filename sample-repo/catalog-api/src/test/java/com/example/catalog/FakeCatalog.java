package com.example.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory products and reviews. Counts every review row it materializes, the way a database
 * driver would, so tests can assert on query shape instead of timing.
 */
final class FakeCatalog implements ProductRepository, ReviewRepository {

    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, List<Review>> reviews = new HashMap<>();
    private long rowsRead;

    void addProduct(Product product) {
        products.put(product.id(), product);
    }

    /** Adds {@code n} deterministic reviews: stars cycle 1..5, helpful votes = index. */
    void addReviews(String productId, int n) {
        List<Review> list = reviews.computeIfAbsent(productId, id -> new ArrayList<>());
        for (int i = 0; i < n; i++) {
            list.add(new Review(productId, "user-" + i, i % 5 + 1, i, "review " + i));
        }
    }

    void addReview(Review review) {
        reviews.computeIfAbsent(review.productId(), id -> new ArrayList<>()).add(review);
    }

    long rowsRead() {
        return rowsRead;
    }

    double expectedAverage(String productId) {
        return rows(productId).stream().mapToInt(Review::stars).average().orElse(0.0);
    }

    @Override
    public Optional<Product> findById(String productId) {
        return Optional.ofNullable(products.get(productId));
    }

    @Override
    public List<Review> findByProductId(String productId) {
        List<Review> all = List.copyOf(rows(productId));
        rowsRead += all.size();
        return all;
    }

    @Override
    public ReviewStats statsFor(String productId) {
        rowsRead += 1; // one aggregate row
        List<Review> all = rows(productId);
        return new ReviewStats(all.size(), all.stream().mapToInt(Review::stars).average().orElse(0.0));
    }

    @Override
    public List<Review> topByHelpfulness(String productId, int limit) {
        List<Review> top = rows(productId).stream()
                .sorted(Comparator.comparingInt(Review::helpfulVotes).reversed())
                .limit(limit)
                .toList();
        rowsRead += top.size();
        return top;
    }

    private List<Review> rows(String productId) {
        return reviews.getOrDefault(productId, List.of());
    }
}
