package com.example.catalog;

import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(String productId);
}
