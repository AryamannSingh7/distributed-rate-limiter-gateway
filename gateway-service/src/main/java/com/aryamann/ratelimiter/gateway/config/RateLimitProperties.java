package com.aryamann.ratelimiter.gateway.config;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalized rate limiting configuration ({@code ratelimit.*}).
 *
 * <p>The rule enforced for a request (algorithm + limit + window) is resolved from two dimensions,
 * both pure configuration so behaviour changes with no rebuild:
 * <ul>
 *   <li><b>tier</b> — the caller's plan (e.g. {@code free} / {@code premium}), chosen by mapping the
 *       caller's API key through {@link #apiKeys}; unknown or missing keys fall back to
 *       {@link #defaultTier}.</li>
 *   <li><b>route</b> — a route may override the rule for a given tier via {@link #routes}, e.g. an
 *       expensive endpoint can be stricter even for premium callers.</li>
 * </ul>
 *
 * <p>Resolution precedence (most specific first) lives in {@code RateLimitResolver}:
 * {@code routes[routeId].tiers[tier]} → {@code tiers[tier]} → {@code tiers[defaultTier]}.
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** Master switch — when false the filter passes every request straight through. */
    private boolean enabled = true;

    /**
     * On a Redis/limiter error: when true (default) admit the request (availability over strictness);
     * when false reject it. A deliberate availability tradeoff documented in the README.
     */
    private boolean failOpen = true;

    /** Tier applied to callers whose API key is unmapped (or who send no key). */
    private String defaultTier = "free";

    /**
     * When true, derive the client IP from the {@code X-Forwarded-For} header (leftmost entry) instead
     * of the socket's remote address. Default false: {@code X-Forwarded-For} is client-spoofable, so it
     * must only be trusted when this gateway sits behind a proxy/LB that overwrites it. Behind a proxy
     * with this left false, every caller collapses onto the proxy's IP and shares one bucket.
     */
    private boolean trustForwardedFor = false;

    /** Named tiers and the rule each enforces. */
    private Map<String, Rule> tiers = new LinkedHashMap<>();

    /** API key → tier name. Keys not listed here resolve to {@link #defaultTier}. */
    private Map<String, String> apiKeys = new LinkedHashMap<>();

    /** Per-route overrides of the tier rules, keyed by Spring Cloud Gateway route id. */
    private Map<String, RouteRules> routes = new LinkedHashMap<>();

    /** A single rule's knobs, bindable from YAML; mirrors {@link RuleConfig}. */
    public static class Rule {
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
        private long limit = 100;
        private Duration window = Duration.ofSeconds(1);

        /** Build the immutable core rule this entry describes. */
        public RuleConfig toRule() {
            return RuleConfig.of(algorithm, limit, window);
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
    }

    /** A route's per-tier rule overrides. */
    public static class RouteRules {
        private Map<String, Rule> tiers = new LinkedHashMap<>();

        public Map<String, Rule> getTiers() {
            return tiers;
        }

        public void setTiers(Map<String, Rule> tiers) {
            this.tiers = tiers;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    public void setDefaultTier(String defaultTier) {
        this.defaultTier = defaultTier;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public Map<String, Rule> getTiers() {
        return tiers;
    }

    public void setTiers(Map<String, Rule> tiers) {
        this.tiers = tiers;
    }

    public Map<String, String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(Map<String, String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public Map<String, RouteRules> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteRules> routes) {
        this.routes = routes;
    }
}
