package com.aryamann.ratelimiter.core.algo;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RateLimitResult;
import com.aryamann.ratelimiter.core.RuleConfig;
import com.aryamann.ratelimiter.core.support.RedisTestSupport;
import com.aryamann.ratelimiter.core.support.SettableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest extends RedisTestSupport {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("allows up to capacity, then blocks with a positive Retry-After")
    void allowsUpToCapacityThenBlocks() {
        SettableClock clock = new SettableClock(T0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(1));
        String key = randomKey();

        for (int i = 1; i <= 5; i++) {
            RateLimitResult r = limiter.tryAcquire(key, rule).block();
            assertThat(r).isNotNull();
            assertThat(r.allowed()).as("request %d", i).isTrue();
            assertThat(r.remaining()).isEqualTo(5 - i);
            assertThat(r.limit()).isEqualTo(5);
        }

        RateLimitResult blocked = limiter.tryAcquire(key, rule).block();
        assertThat(blocked).isNotNull();
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterMs()).isPositive();
    }

    @Test
    @DisplayName("refills tokens as time advances")
    void refillsOverTime() {
        SettableClock clock = new SettableClock(T0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(1));
        String key = randomKey();

        // drain the bucket
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();

        // after a full window the bucket has refilled to capacity
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed())
                    .as("post-refill request %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();
    }

    @Test
    @DisplayName("partial elapsed time refills proportionally")
    void partialRefill() {
        SettableClock clock = new SettableClock(T0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1));
        String key = randomKey();

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(key, rule).block();
        }
        // 10 tokens / 1000ms => 0.01 tokens/ms; 300ms => 3 tokens
        clock.advance(Duration.ofMillis(300));

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire(key, rule).block().allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    @DisplayName("atomic under concurrency: exactly `limit` of many parallel requests are allowed")
    void atomicUnderConcurrency() {
        SettableClock clock = new SettableClock(T0); // fixed time => no refill during the burst
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.TOKEN_BUCKET, 100, Duration.ofSeconds(1));
        String key = randomKey();

        int totalRequests = 500;
        Long allowed = Flux.range(0, totalRequests)
                .flatMap(i -> limiter.tryAcquire(key, rule), 32)
                .filter(RateLimitResult::allowed)
                .count()
                .block();

        assertThat(allowed)
                .as("Redis Lua must admit exactly the capacity, with no race/double-count")
                .isEqualTo(100L);
    }
}
