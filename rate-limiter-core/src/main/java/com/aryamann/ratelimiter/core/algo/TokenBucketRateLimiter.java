package com.aryamann.ratelimiter.core.algo;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RateLimitResult;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Token Bucket: a bucket of {@code limit} tokens refills continuously at {@code limit / window}
 * tokens per ms, capped at {@code limit}. Each request costs one token. Allows short bursts up to
 * capacity while bounding the long-run rate — the usual choice for smooth API throttling.
 */
public class TokenBucketRateLimiter extends AbstractLuaRateLimiter {

    private static final long REQUESTED = 1L;

    public TokenBucketRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        super(redis, clock);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.TOKEN_BUCKET;
    }

    @Override
    protected String scriptLocation() {
        return "lua/token_bucket.lua";
    }

    @Override
    protected List<String> keys(String key) {
        return List.of(key);
    }

    @Override
    protected List<String> argv(RuleConfig rule, long nowMs) {
        long capacity = rule.limit();
        long refillTokens = rule.limit();       // refill `limit` tokens per window
        long refillPeriodMs = rule.windowMs();
        // Keep idle buckets around for two windows so state survives brief inactivity, then expire.
        long ttlMs = Math.max(refillPeriodMs * 2, 1_000L);
        return List.of(
                Long.toString(capacity),
                Long.toString(refillTokens),
                Long.toString(refillPeriodMs),
                Long.toString(nowMs),
                Long.toString(REQUESTED),
                Long.toString(ttlMs));
    }

    @Override
    protected RateLimitResult toResult(RuleConfig rule, List<Long> raw) {
        boolean allowed = raw.get(0) == 1L;
        long remaining = raw.get(1);
        long retryAfterMs = raw.get(2);
        long resetAfterMs = raw.get(3);
        return new RateLimitResult(allowed, rule.limit(), remaining, retryAfterMs, resetAfterMs);
    }
}
