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

class FixedWindowRateLimiterTest extends RedisTestSupport {

    // Aligned to a whole second so the window boundary sits exactly at T0 (windowMs = 1000).
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("allows up to limit within a window, then blocks")
    void allowsUpToLimitThenBlocks() {
        SettableClock clock = new SettableClock(T0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1));
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
    @DisplayName("counter resets when the window rolls over")
    void resetsOnNewWindow() {
        SettableClock clock = new SettableClock(T0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.FIXED_WINDOW, 3, Duration.ofSeconds(1));
        String key = randomKey();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();

        // crossing into the next aligned window resets the counter to a full allowance
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed())
                    .as("new-window request %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();
    }

    @Test
    @DisplayName("partial progress through a window does not reset the counter")
    void doesNotResetWithinWindow() {
        SettableClock clock = new SettableClock(T0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.FIXED_WINDOW, 3, Duration.ofSeconds(1));
        String key = randomKey();

        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();

        // still inside the same aligned window: the count persists, only one slot remains
        clock.advance(Duration.ofMillis(500));
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();
    }

    @Test
    @DisplayName("documents the boundary-burst flaw: up to 2*limit across a window edge")
    void boundaryBurstAllowsDoubleLimit() {
        SettableClock clock = new SettableClock(T0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1));
        String key = randomKey();

        // end of window 0 (t = 999ms): consume the full allowance
        clock.advance(Duration.ofMillis(999));
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }

        // start of window 1 (t = 1000ms), 1ms later: a fresh full allowance
        clock.advance(Duration.ofMillis(1));
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }
        // => 10 requests admitted within 1ms, double the nominal limit
    }

    @Test
    @DisplayName("atomic under concurrency: exactly `limit` of many parallel requests are allowed")
    void atomicUnderConcurrency() {
        SettableClock clock = new SettableClock(T0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.FIXED_WINDOW, 100, Duration.ofSeconds(1));
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
