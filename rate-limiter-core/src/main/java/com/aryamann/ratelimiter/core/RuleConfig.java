package com.aryamann.ratelimiter.core;

import java.time.Duration;

/**
 * A single rate limiting rule: which algorithm, how many requests ({@code limit}) are permitted
 * per {@code window}. The same two-knob surface (limit + window) applies uniformly to every
 * algorithm, which is what lets a rule be swapped purely through configuration.
 *
 * <p>For {@link Algorithm#TOKEN_BUCKET}, {@code limit} is the bucket capacity (max burst) and the
 * bucket refills {@code limit} tokens over one {@code window}.
 *
 * @param algorithm the algorithm to apply
 * @param limit     max requests permitted per window (must be &gt; 0)
 * @param window    the window / refill period (must be &gt; 0)
 */
public record RuleConfig(Algorithm algorithm, long limit, Duration window) {

    public RuleConfig {
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, was " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration, was " + window);
        }
    }

    /** Convenience factory: {@code limit} requests per {@code window} using the given algorithm. */
    public static RuleConfig of(Algorithm algorithm, long limit, Duration window) {
        return new RuleConfig(algorithm, limit, window);
    }

    /** Window length in milliseconds — the unit passed into the Lua scripts. */
    public long windowMs() {
        return window.toMillis();
    }
}
