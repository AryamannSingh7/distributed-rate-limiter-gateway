package com.aryamann.ratelimiter.core;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Indexes the available {@link RateLimiter} implementations by {@link Algorithm} so a rule can
 * select its algorithm purely from configuration — switching never requires a code change. The
 * gateway builds one of these from all the limiter beans on the classpath and asks it for the
 * limiter named by each {@link RuleConfig}.
 */
public class RateLimiterRegistry {

    private final Map<Algorithm, RateLimiter> byAlgorithm;

    public RateLimiterRegistry(Collection<RateLimiter> limiters) {
        Map<Algorithm, RateLimiter> map = new EnumMap<>(Algorithm.class);
        for (RateLimiter limiter : limiters) {
            RateLimiter previous = map.put(limiter.algorithm(), limiter);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate RateLimiter registered for algorithm " + limiter.algorithm());
            }
        }
        this.byAlgorithm = map;
    }

    /**
     * @return the limiter for {@code algorithm}
     * @throws IllegalArgumentException if no limiter is registered for it
     */
    public RateLimiter get(Algorithm algorithm) {
        RateLimiter limiter = byAlgorithm.get(algorithm);
        if (limiter == null) {
            throw new IllegalArgumentException(
                    "No RateLimiter registered for algorithm " + algorithm + "; available: " + byAlgorithm.keySet());
        }
        return limiter;
    }

    /** The algorithms that have a registered limiter. */
    public Set<Algorithm> available() {
        return Set.copyOf(byAlgorithm.keySet());
    }
}
