package com.aryamann.ratelimiter.gateway.config;

import com.aryamann.ratelimiter.core.RuleConfig;
import com.aryamann.ratelimiter.gateway.config.RateLimitProperties.Rule;
import com.aryamann.ratelimiter.gateway.config.RateLimitProperties.RouteRules;
import org.springframework.stereotype.Component;

/**
 * Resolves which rule to enforce for a request from configuration alone (see {@link RateLimitProperties}).
 * Two dimensions feed in: the caller's <b>tier</b> (derived from their API key) and the <b>route</b>
 * being called. Precedence, most specific first:
 *
 * <pre>{@code routes[routeId].tiers[tier]  ->  tiers[tier]  ->  tiers[defaultTier]}</pre>
 *
 * Keeping resolution here (rather than in the filter) makes it pure and unit-testable with no Redis.
 */
@Component
public class RateLimitResolver {

    private final RateLimitProperties properties;

    public RateLimitResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** The outcome of resolving a request: the tier it fell into and the rule to enforce. */
    public record Decision(String tier, RuleConfig rule) {
    }

    /**
     * @param routeId the gateway route id, or {@code null} if the request matched no route
     * @param apiKey  the caller's raw API key, or {@code null} if none was supplied
     */
    public Decision resolve(String routeId, String apiKey) {
        String tier = tierFor(apiKey);
        Rule rule = ruleFor(routeId, tier);
        if (rule == null) {
            throw new IllegalStateException(
                    "No rate limit rule for tier '" + tier + "'"
                            + (routeId != null ? " on route '" + routeId + "'" : "")
                            + "; configure ratelimit.tiers." + tier
                            + " (or ratelimit.tiers." + properties.getDefaultTier() + ")");
        }
        return new Decision(tier, rule.toRule());
    }

    /** Map the caller's API key to a tier; unknown or missing keys get the default tier. */
    public String tierFor(String apiKey) {
        if (apiKey != null) {
            String tier = properties.getApiKeys().get(apiKey);
            if (tier != null) {
                return tier;
            }
        }
        return properties.getDefaultTier();
    }

    private Rule ruleFor(String routeId, String tier) {
        RouteRules route = routeId == null ? null : properties.getRoutes().get(routeId);
        if (route != null) {
            Rule override = route.getTiers().get(tier);
            if (override != null) {
                return override;
            }
        }
        Rule tierRule = properties.getTiers().get(tier);
        if (tierRule != null) {
            return tierRule;
        }
        // unmapped tier name -> fall back to the default tier's rule
        return properties.getTiers().get(properties.getDefaultTier());
    }
}
