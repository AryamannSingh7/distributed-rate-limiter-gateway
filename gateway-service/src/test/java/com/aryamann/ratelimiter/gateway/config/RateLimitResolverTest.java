package com.aryamann.ratelimiter.gateway.config;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RuleConfig;
import com.aryamann.ratelimiter.gateway.config.RateLimitProperties.Rule;
import com.aryamann.ratelimiter.gateway.config.RateLimitProperties.RouteRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure resolution logic — no Spring context, no Redis. Proves tier selection from the API key and the
 * route → tier → default precedence that lets limits be reshaped entirely from configuration.
 */
class RateLimitResolverTest {

    private static Rule rule(Algorithm algorithm, long limit, Duration window) {
        Rule r = new Rule();
        r.setAlgorithm(algorithm);
        r.setLimit(limit);
        r.setWindow(window);
        return r;
    }

    /** free = SWC 20/s, premium = TB 200/s, "premium-key" -> premium, default tier = free. */
    private static RateLimitProperties baseProps() {
        RateLimitProperties p = new RateLimitProperties();
        p.setDefaultTier("free");
        p.getTiers().put("free", rule(Algorithm.SLIDING_WINDOW_COUNTER, 20, Duration.ofSeconds(1)));
        p.getTiers().put("premium", rule(Algorithm.TOKEN_BUCKET, 200, Duration.ofSeconds(1)));
        p.getApiKeys().put("premium-key", "premium");
        return p;
    }

    @Test
    void unmappedOrMissingApiKeyResolvesToDefaultTier() {
        RateLimitResolver resolver = new RateLimitResolver(baseProps());

        assertThat(resolver.tierFor(null)).isEqualTo("free");
        assertThat(resolver.tierFor("some-unknown-key")).isEqualTo("free");

        RateLimitResolver.Decision decision = resolver.resolve("demo-api", null);
        assertThat(decision.tier()).isEqualTo("free");
        assertThat(decision.rule()).isEqualTo(RuleConfig.of(Algorithm.SLIDING_WINDOW_COUNTER, 20, Duration.ofSeconds(1)));
    }

    @Test
    void mappedApiKeyResolvesToItsTier() {
        RateLimitResolver resolver = new RateLimitResolver(baseProps());

        RateLimitResolver.Decision decision = resolver.resolve("demo-api", "premium-key");
        assertThat(decision.tier()).isEqualTo("premium");
        assertThat(decision.rule()).isEqualTo(RuleConfig.of(Algorithm.TOKEN_BUCKET, 200, Duration.ofSeconds(1)));
    }

    @Test
    void routeOverrideWinsOverGlobalTierRule() {
        RateLimitProperties p = baseProps();
        RouteRules route = new RouteRules();
        route.getTiers().put("free", rule(Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1)));
        p.getRoutes().put("demo-api", route);
        RateLimitResolver resolver = new RateLimitResolver(p);

        // free caller on the overridden route gets the stricter route rule...
        assertThat(resolver.resolve("demo-api", null).rule())
                .isEqualTo(RuleConfig.of(Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1)));
        // ...but on a route with no override falls back to the global free tier rule.
        assertThat(resolver.resolve("other-route", null).rule())
                .isEqualTo(RuleConfig.of(Algorithm.SLIDING_WINDOW_COUNTER, 20, Duration.ofSeconds(1)));
        // premium caller has no override for this route -> keeps the global premium rule.
        assertThat(resolver.resolve("demo-api", "premium-key").rule())
                .isEqualTo(RuleConfig.of(Algorithm.TOKEN_BUCKET, 200, Duration.ofSeconds(1)));
    }

    @Test
    void unmappedTierFallsBackToDefaultTierRule() {
        RateLimitProperties p = baseProps();
        p.getApiKeys().put("ghost-key", "gold"); // "gold" tier is never defined
        RateLimitResolver resolver = new RateLimitResolver(p);

        RateLimitResolver.Decision decision = resolver.resolve("demo-api", "ghost-key");
        assertThat(decision.tier()).isEqualTo("gold");
        assertThat(decision.rule()).isEqualTo(RuleConfig.of(Algorithm.SLIDING_WINDOW_COUNTER, 20, Duration.ofSeconds(1)));
    }

    @Test
    void throwsWhenNoRuleAndNoDefaultRuleConfigured() {
        RateLimitProperties p = new RateLimitProperties();
        p.setDefaultTier("free"); // but no tiers map entries at all
        RateLimitResolver resolver = new RateLimitResolver(p);

        assertThatThrownBy(() -> resolver.resolve("demo-api", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No rate limit rule");
    }
}
