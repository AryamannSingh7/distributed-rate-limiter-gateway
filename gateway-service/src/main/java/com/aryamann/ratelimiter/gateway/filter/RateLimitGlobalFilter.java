package com.aryamann.ratelimiter.gateway.filter;

import com.aryamann.ratelimiter.core.RateLimitResult;
import com.aryamann.ratelimiter.core.RateLimiterRegistry;
import com.aryamann.ratelimiter.core.RuleConfig;
import com.aryamann.ratelimiter.gateway.config.RateLimitProperties;
import com.aryamann.ratelimiter.gateway.config.RateLimitResolver;
import com.aryamann.ratelimiter.gateway.resolver.ClientKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The gateway's rate limiting entry point. Runs early on every routed request, asks the core
 * {@link RateLimiter} for an atomic decision, and either lets the request proxy through (annotated
 * with {@code X-RateLimit-*} headers) or short-circuits with {@code 429 Too Many Requests}.
 *
 * <p>Ordering: this runs before the routing filters so a blocked request never reaches the backend.
 * On a limiter error it honours {@link RateLimitProperties#isFailOpen()} — the default fails open so
 * a Redis outage degrades to "no limiting" rather than taking down all traffic.
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGlobalFilter.class);

    static final String HEADER_LIMIT = "X-RateLimit-Limit";
    static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_RESET = "X-RateLimit-Reset";

    private final RateLimiterRegistry registry;
    private final ClientKeyResolver keyResolver;
    private final RateLimitResolver ruleResolver;
    private final RateLimitProperties properties;

    public RateLimitGlobalFilter(RateLimiterRegistry registry,
                                 ClientKeyResolver keyResolver,
                                 RateLimitResolver ruleResolver,
                                 RateLimitProperties properties) {
        this.registry = registry;
        this.keyResolver = keyResolver;
        this.ruleResolver = ruleResolver;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : null;
        RateLimitResolver.Decision decision = ruleResolver.resolve(routeId, keyResolver.apiKey(exchange));
        RuleConfig rule = decision.rule();
        String key = buildKey(decision.tier(), routeId, rule, keyResolver.resolve(exchange));

        return registry.get(rule.algorithm()).tryAcquire(key, rule)
                .onErrorResume(ex -> failOpenOrError(ex, key, rule))
                .flatMap(result -> result.allowed()
                        ? proceed(exchange, chain, result)
                        : reject(exchange, result));
    }

    private Mono<Void> proceed(ServerWebExchange exchange, GatewayFilterChain chain, RateLimitResult result) {
        // Headers must be set before the response commits; the routing filter writes the body later.
        exchange.getResponse().beforeCommit(() -> {
            applyRateLimitHeaders(exchange.getResponse().getHeaders(), result, false);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, RateLimitResult result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);

        HttpHeaders headers = response.getHeaders();
        applyRateLimitHeaders(headers, result, true);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":"
                + result.retryAfterSeconds() + "}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private void applyRateLimitHeaders(HttpHeaders headers, RateLimitResult result, boolean blocked) {
        headers.set(HEADER_LIMIT, Long.toString(result.limit()));
        headers.set(HEADER_REMAINING, Long.toString(Math.max(0, result.remaining())));
        headers.set(HEADER_RESET, Long.toString(result.resetAfterSeconds()));
        if (blocked) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(result.retryAfterSeconds()));
        }
    }

    private Mono<RateLimitResult> failOpenOrError(Throwable ex, String key, RuleConfig rule) {
        if (properties.isFailOpen()) {
            log.warn("Rate limiter error for key '{}', failing open: {}", key, ex.toString());
            return Mono.just(new RateLimitResult(true, rule.limit(), rule.limit(), 0L, rule.windowMs()));
        }
        log.warn("Rate limiter error for key '{}', failing closed: {}", key, ex.toString());
        return Mono.error(ex);
    }

    private String buildKey(String tier, String routeId, RuleConfig rule, String identity) {
        // tier + algorithm are part of the namespace: changing either (e.g. promoting a key to a new
        // tier, or switching algorithm) starts a clean bucket rather than reusing incompatible state.
        String rid = routeId != null ? routeId : "unknown";
        return "rl:" + tier + ":" + rule.algorithm().name().toLowerCase(Locale.ROOT) + ":" + rid + ":" + identity;
    }

    @Override
    public int getOrder() {
        // Before the routing filters so blocked requests never hit the backend.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
