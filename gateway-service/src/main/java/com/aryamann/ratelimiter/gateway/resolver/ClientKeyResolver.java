package com.aryamann.ratelimiter.gateway.resolver;

import com.aryamann.ratelimiter.gateway.config.RateLimitProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * Resolves the client identity a rate limit bucket is keyed on.
 *
 * <p>Resolution chain: a <b>recognized</b> {@code X-API-Key} → else the remote IP. The returned value
 * is prefixed ({@code key:} / {@code ip:}) so the two identity spaces can never collide, and is later
 * combined with algorithm + route by the filter into the final Redis key.
 *
 * <p><b>Why only recognized keys earn their own bucket:</b> if any caller-supplied key were trusted as
 * the identity, a client could escape its IP limit — or get an unlimited stream of fresh buckets — just
 * by sending a different random {@code X-API-Key} on every request. Unknown/forged keys therefore fall
 * back to the IP bucket, so an anonymous caller is limited regardless of what header they invent.
 */
@Component
public class ClientKeyResolver {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final RateLimitProperties properties;

    public ClientKeyResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** @return a stable, prefixed identity for the caller; never null. */
    public String resolve(ServerWebExchange exchange) {
        String apiKey = apiKey(exchange);
        // Only a key we actually know (mapped to a tier) is trusted as a distinct identity; anything
        // else is treated as anonymous and shares the caller's IP bucket. See class javadoc.
        if (apiKey != null && properties.getApiKeys().containsKey(apiKey)) {
            return "key:" + apiKey;
        }
        return clientIp(exchange);
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

    /**
     * The caller's IP identity ({@code ip:<addr>}). Behind a proxy the socket address is the proxy's,
     * so when {@code ratelimit.trust-forwarded-for} is enabled the originating client is taken from the
     * leftmost {@code X-Forwarded-For} entry instead. That header is only trustworthy when a known proxy
     * sets it (it is otherwise client-spoofable), which is why honouring it is opt-in.
     */
    private String clientIp(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        if (properties.isTrustForwardedFor()) {
            String forwarded = request.getHeaders().getFirst(FORWARDED_FOR_HEADER);
            if (forwarded != null && !forwarded.isBlank()) {
                // "client, proxy1, proxy2" — the leftmost hop is the originating client.
                String client = forwarded.split(",", 2)[0].trim();
                if (!client.isEmpty()) {
                    return "ip:" + client;
                }
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return "ip:" + remote.getAddress().getHostAddress();
        }
        return "ip:unknown";
    }
}
