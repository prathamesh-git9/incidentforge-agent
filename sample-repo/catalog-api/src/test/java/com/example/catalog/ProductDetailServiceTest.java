package com.example.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductDetailServiceTest {

    @Test
    void summarizesReviewsAndShowsTheMostHelpfulOnes() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.addProduct(new Product("P-1", "Kettle", 4999));
        catalog.addReview(new Review("P-1", "a", 5, 2, "great"));
        catalog.addReview(new Review("P-1", "b", 3, 9, "ok"));
        catalog.addReview(new Review("P-1", "c", 4, 7, "good"));
        catalog.addReview(new Review("P-1", "d", 2, 1, "meh"));

        ProductDetail detail = new ProductDetailService(catalog, catalog).detail("P-1");

        assertEquals(4, detail.reviewCount());
        assertEquals(3.5, detail.averageStars(), 1e-9);
        assertEquals(List.of("b", "c", "a"), detail.topReviews().stream().map(Review::author).toList());
    }

    @Test
    void productWithoutReviewsHasAnEmptySummary() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.addProduct(new Product("P-2", "Mug", 999));

        ProductDetail detail = new ProductDetailService(catalog, catalog).detail("P-2");

        assertEquals(0, detail.reviewCount());
        assertEquals(0.0, detail.averageStars(), 1e-9);
        assertEquals(List.of(), detail.topReviews());
    }

    @Test
    void unknownProductIsNotFound() {
        FakeCatalog catalog = new FakeCatalog();
        assertThrows(ProductNotFoundException.class,
                () -> new ProductDetailService(catalog, catalog).detail("P-404"));
    }
}
