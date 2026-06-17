package com.aryamann.ratelimiter.core.algo;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Fixed Window: a single counter per aligned {@code window} of length {@code window}, allowing up to
 * {@code limit} requests before the window rolls. Cheapest to compute and reason about, but allows a
 * burst of up to {@code 2 * limit} across a window boundary — the baseline against which the
 * sliding-window algorithms are compared.
 */
public class FixedWindowRateLimiter extends AbstractLuaRateLimiter {

    public FixedWindowRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        super(redis, clock);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.FIXED_WINDOW;
    }

    @Override
    protected String scriptLocation() {
        return "lua/fixed_window.lua";
    }

    @Override
    protected List<String> keys(String key) {
        return List.of(key);
    }

    @Override
    protected List<String> argv(RuleConfig rule, long nowMs) {
        // Keep idle counters around for two windows so brief inactivity survives, then expire.
        long ttlMs = Math.max(rule.windowMs() * 2, 1_000L);
        return List.of(
                Long.toString(rule.limit()),
                Long.toString(rule.windowMs()),
                Long.toString(nowMs),
                Long.toString(ttlMs));
    }
}
