package com.aryamann.ratelimiter.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterRegistryTest {

    /** Minimal stub limiter — the registry only ever reads {@link RateLimiter#algorithm()}. */
    private static RateLimiter stub(Algorithm algorithm) {
        return new RateLimiter() {
            @Override
            public Algorithm algorithm() {
                return algorithm;
            }

            @Override
            public Mono<RateLimitResult> tryAcquire(String key, RuleConfig rule) {
                return Mono.empty();
            }
        };
    }

    @Test
    void resolvesByAlgorithm() {
        RateLimiter tokenBucket = stub(Algorithm.TOKEN_BUCKET);
        RateLimiter fixedWindow = stub(Algorithm.FIXED_WINDOW);
        RateLimiterRegistry registry = new RateLimiterRegistry(List.of(tokenBucket, fixedWindow));

        assertThat(registry.get(Algorithm.TOKEN_BUCKET)).isSameAs(tokenBucket);
        assertThat(registry.get(Algorithm.FIXED_WINDOW)).isSameAs(fixedWindow);
        assertThat(registry.available()).containsExactlyInAnyOrder(Algorithm.TOKEN_BUCKET, Algorithm.FIXED_WINDOW);
    }

    @Test
    void rejectsDuplicateAlgorithm() {
        assertThatThrownBy(() ->
                new RateLimiterRegistry(List.of(stub(Algorithm.TOKEN_BUCKET), stub(Algorithm.TOKEN_BUCKET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void failsClearlyForMissingAlgorithm() {
        RateLimiterRegistry registry = new RateLimiterRegistry(List.of(stub(Algorithm.TOKEN_BUCKET)));

        assertThatThrownBy(() -> registry.get(Algorithm.SLIDING_WINDOW_LOG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No RateLimiter registered");
    }
}
