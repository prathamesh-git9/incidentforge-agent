package com.example.catalog;

import java.util.List;

/** Read access to product reviews. The JDBC implementation is not part of this sample. */
public interface ReviewRepository {

    /**
     * Every review row for a product ({@code SELECT * FROM reviews WHERE product_id = ?}). Used by the
     * moderation export; a popular product has tens of thousands of rows.
     */
    List<Review> findByProductId(String productId);

    /** Aggregate projection ({@code SELECT COUNT(*), AVG(stars) ...}); used by search results. */
    ReviewStats statsFor(String productId);

    /** At most {@code limit} rows, most helpful first; served by an index. Used by the reviews widget. */
    List<Review> topByHelpfulness(String productId, int limit);
}
