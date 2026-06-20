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

class SlidingWindowCounterRateLimiterTest extends RedisTestSupport {

    // Aligned to a window boundary so the first window starts exactly at T0 (elapsed = 0, weight = 1).
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private SlidingWindowCounterRateLimiter limiterAt(SettableClock clock) {
        return new SlidingWindowCounterRateLimiter(redis, clock);
    }

    private RuleConfig rule5per1s() {
        return RuleConfig.of(Algorithm.SLIDING_WINDOW_COUNTER, 5, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("allows up to limit within a window, then blocks (no previous-window weight at T0)")
    void allowsUpToLimitThenBlocks() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowCounterRateLimiter limiter = limiterAt(clock);
        RuleConfig rule = rule5per1s();
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
    @DisplayName("halfway into the next window, the previous window's count is weighted at 50%")
    void weightsPreviousWindowAtFiftyPercentHalfwayThrough() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowCounterRateLimiter limiter = limiterAt(clock);
        RuleConfig rule = rule5per1s();
        String key = randomKey();

        // Fill window 1 (5 requests at T0).
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }

        // Move to T0 + 1500ms: 500ms into window 2. prev=5, weight=0.5 -> carried estimate = 2.5,
        // so exactly 3 more requests fit (2.5 -> 3.5 -> 4.5 -> 5.5, the 4th is blocked).
        clock.advance(Duration.ofMillis(1500));
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire(key, rule).block().allowed()) {
                allowed++;
            }
        }
        assertThat(allowed)
                .as("2.5 carried from the previous window leaves room for 3 in the current")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a gap of two or more windows drops the previous count entirely (full capacity returns)")
    void gapOfTwoWindowsResetsPreviousCount() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowCounterRateLimiter limiter = limiterAt(clock);
        RuleConfig rule = rule5per1s();
        String key = randomKey();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }

        // Skip a whole window (no overlap with window 1) -> previous weight contributes nothing.
        clock.advance(Duration.ofMillis(2000));
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed())
                    .as("post-gap request %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("atomic under concurrency: exactly `limit` of many parallel requests are allowed")
    void atomicUnderConcurrency() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowCounterRateLimiter limiter = limiterAt(clock);
        RuleConfig rule = RuleConfig.of(Algorithm.SLIDING_WINDOW_COUNTER, 100, Duration.ofSeconds(1));
        String key = randomKey();

        Long allowed = Flux.range(0, 500)
                .flatMap(i -> limiter.tryAcquire(key, rule), 32)
                .filter(RateLimitResult::allowed)
                .count()
                .block();

        assertThat(allowed)
                .as("Redis Lua must admit exactly the limit, with no race/double-count")
                .isEqualTo(100L);
    }
}
