package com.aryamann.ratelimiter.gateway.config;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized rate limiting configuration ({@code ratelimit.*}).
 *
 * <p>For M2 this is a single global rule applied to every route; M3 expands it into per-tier /
 * per-route rules. The two-knob surface ({@code limit} per {@code window}) plus a swappable
 * {@code algorithm} is what lets behaviour change with no code change.
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** Master switch — when false the filter passes every request straight through. */
    private boolean enabled = true;

    /** Which algorithm to enforce. Switchable without code changes. */
    private Algorithm algorithm = Algorithm.TOKEN_BUCKET;

    /** Max requests permitted per {@link #window} (bucket capacity for token bucket). */
    private long limit = 100;

    /** The window / refill period, e.g. {@code 1s}, {@code 500ms}, {@code 1m}. */
    private Duration window = Duration.ofSeconds(1);

    /**
     * On a Redis/limiter error: when true (default) admit the request (availability over strictness);
     * when false reject it. A deliberate availability tradeoff documented in the README.
     */
    private boolean failOpen = true;

    /** Build the immutable core rule this configuration describes. */
    public RuleConfig toRule() {
        return RuleConfig.of(algorithm, limit, window);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
    }

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }
}
