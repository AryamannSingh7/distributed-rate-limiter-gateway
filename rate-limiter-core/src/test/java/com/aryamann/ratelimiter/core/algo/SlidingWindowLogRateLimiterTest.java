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

class SlidingWindowLogRateLimiterTest extends RedisTestSupport {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("allows up to limit within the window, then blocks")
    void allowsUpToLimitThenBlocks() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofSeconds(1));
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
    @DisplayName("slot frees exactly one window after the oldest request (true sliding, no boundary burst)")
    void freesExactlyOneWindowAfterOldest() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofSeconds(1));
        String key = randomKey();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }

        // 1ms shy of a full window: the T0 entries are still in range -> still blocked
        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();

        // exactly one window after T0: the oldest entries age out -> capacity returns
        clock.advance(Duration.ofMillis(1));
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed())
                    .as("post-slide request %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("entries age out individually as the window rolls forward")
    void entriesAgeOutIndividually() {
        SettableClock clock = new SettableClock(T0);
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofSeconds(1));
        String key = randomKey();

        // 3 requests at T0
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }
        // 2 more at T0+600ms -> 5 in the window, next is blocked
        clock.advance(Duration.ofMillis(600));
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.tryAcquire(key, rule).block().allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire(key, rule).block().allowed()).isFalse();

        // advance to T0+1100ms: only the 3 T0 entries have aged out, freeing exactly 3 slots
        clock.advance(Duration.ofMillis(500));
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
        SettableClock clock = new SettableClock(T0);
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(redis, clock);
        RuleConfig rule = RuleConfig.of(Algorithm.SLIDING_WINDOW_LOG, 100, Duration.ofSeconds(1));
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
