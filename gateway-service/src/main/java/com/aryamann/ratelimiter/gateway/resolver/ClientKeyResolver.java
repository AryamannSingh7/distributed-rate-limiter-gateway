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
        ServerHttpRequest request = exchange.getRequest();

        String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey.trim();
        }

        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return "ip:" + remote.getAddress().getHostAddress();
        }
        return "ip:unknown";
    }
}
