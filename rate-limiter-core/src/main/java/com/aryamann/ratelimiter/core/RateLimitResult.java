package com.aryamann.ratelimiter.core;

/**
 * Outcome of a single rate limit decision. Carries everything needed to build the standard
 * HTTP rate limit response headers.
 *
 * @param allowed       whether the request is permitted
 * @param limit         the configured limit (for {@code X-RateLimit-Limit})
 * @param remaining     requests remaining in the current window (for {@code X-RateLimit-Remaining})
 * @param retryAfterMs  ms the caller should wait before retrying; 0 when allowed (for {@code Retry-After})
 * @param resetAfterMs  ms until the limit fully resets / refills (for {@code X-RateLimit-Reset})
 */
public record RateLimitResult(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterMs,
        long resetAfterMs) {

    /** {@code Retry-After} is expressed in whole seconds; round up so we never advertise too-early retries. */
    public long retryAfterSeconds() {
        return (retryAfterMs + 999) / 1000;
    }

    /** {@code X-RateLimit-Reset} as whole seconds until reset. */
    public long resetAfterSeconds() {
        return (resetAfterMs + 999) / 1000;
    }
}
