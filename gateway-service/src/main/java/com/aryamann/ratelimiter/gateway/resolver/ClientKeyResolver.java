package com.aryamann.ratelimiter.gateway.resolver;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * Resolves the client identity a rate limit bucket is keyed on.
 *
 * <p>Resolution chain: {@code X-API-Key} header → else the remote IP. The returned value is
 * prefixed ({@code key:} / {@code ip:}) so the two identity spaces can never collide, and is later
 * combined with algorithm + route by the filter into the final Redis key.
 */
@Component
public class ClientKeyResolver {

    public static final String API_KEY_HEADER = "X-API-Key";

    /** @return a stable, prefixed identity for the caller; never null. */
    public String resolve(ServerWebExchange exchange) {
        String apiKey = apiKey(exchange);
        if (apiKey != null) {
            return "key:" + apiKey;
        }

        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return "ip:" + remote.getAddress().getHostAddress();
        }
        return "ip:unknown";
    }

    /**
     * The caller's raw {@code X-API-Key}, trimmed, or {@code null} if none was supplied. Used to map
     * the caller onto a tier; {@link #resolve} turns it into the bucket identity.
     */
    public String apiKey(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
        return (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : null;
    }
}
