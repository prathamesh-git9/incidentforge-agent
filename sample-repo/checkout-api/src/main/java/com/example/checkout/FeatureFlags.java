package com.example.checkout;

@FunctionalInterface
public interface FeatureFlags {

    boolean enabled(String flag, String subject);
}
