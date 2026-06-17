package com.aryamann.ratelimiter.core.algo;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Sliding Window Log: keeps the timestamp of every admitted request in a sorted set and counts how
 * many fall inside the exact trailing window. The most accurate algorithm — it has no boundary burst
 * — but its memory grows with the request rate (one entry per request). Best when accuracy matters
 * more than footprint.
 */
public class SlidingWindowLogRateLimiter extends AbstractLuaRateLimiter {

    public SlidingWindowLogRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        super(redis, clock);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW_LOG;
    }

    @Override
    protected String scriptLocation() {
        return "lua/sliding_window_log.lua";
    }

    @Override
    protected List<String> keys(String key) {
        return List.of(key);
    }

    @Override
    protected List<String> argv(RuleConfig rule, long nowMs) {
        long ttlMs = Math.max(rule.windowMs() * 2, 1_000L);
        // A unique member so two requests in the same millisecond are distinct entries, not one.
        String member = nowMs + "-" + UUID.randomUUID();
        return List.of(
                Long.toString(rule.limit()),
                Long.toString(rule.windowMs()),
                Long.toString(nowMs),
                Long.toString(ttlMs),
                member);
    }
}
