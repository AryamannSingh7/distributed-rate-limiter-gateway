package com.aryamann.ratelimiter.core;

import reactor.core.publisher.Mono;

/**
 * A rate limiter for a single algorithm. Implementations enforce the limit atomically in Redis so
 * the decision is correct even when many gateway instances share one Redis.
 */
public interface RateLimiter {

    /** Which algorithm this limiter implements. */
    Algorithm algorithm();

    /**
     * Attempt to admit one request against {@code key} under {@code rule}.
     *
     * @param key  fully-resolved bucket key (e.g. {@code rl:token_bucket:route:apiKey})
     * @param rule the limit/window/algorithm to apply
     * @return the decision; never empty
     */
    Mono<RateLimitResult> tryAcquire(String key, RuleConfig rule);
}
