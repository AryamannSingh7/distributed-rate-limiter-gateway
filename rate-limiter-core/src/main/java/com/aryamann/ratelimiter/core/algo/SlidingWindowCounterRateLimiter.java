package com.aryamann.ratelimiter.core.algo;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Sliding Window Counter: weights the current fixed-window counter against a decaying fraction of the
 * previous window's counter to approximate a true sliding window. It keeps only two integers per key
 * (vs. Sliding Window Log's one entry per request) yet avoids Fixed Window's boundary burst, making it
 * the usual production default — accurate enough, and cheap.
 */
public class SlidingWindowCounterRateLimiter extends AbstractLuaRateLimiter {

    public SlidingWindowCounterRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        super(redis, clock);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW_COUNTER;
    }

    @Override
    protected String scriptLocation() {
        return "lua/sliding_window_counter.lua";
    }

    @Override
    protected List<String> keys(String key) {
        return List.of(key);
    }

    @Override
    protected List<String> argv(RuleConfig rule, long nowMs) {
        // Keep counters for two windows so the previous window survives to be weighted into the current.
        long ttlMs = Math.max(rule.windowMs() * 2, 1_000L);
        return List.of(
                Long.toString(rule.limit()),
                Long.toString(rule.windowMs()),
                Long.toString(nowMs),
                Long.toString(ttlMs));
    }
}
