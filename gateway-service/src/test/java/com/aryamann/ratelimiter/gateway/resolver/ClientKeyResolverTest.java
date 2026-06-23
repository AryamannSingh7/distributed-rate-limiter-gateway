package com.aryamann.ratelimiter.gateway.resolver;

import com.aryamann.ratelimiter.gateway.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity resolution — the bucket key a request is counted against. The headline case here is the
 * key-rotation defence: an unrecognized {@code X-API-Key} must NOT mint its own bucket, otherwise a
 * client could evade limits entirely by sending a fresh random key per request.
 */
class ClientKeyResolverTest {

    private static RateLimitProperties propsWithKnownKey() {
        RateLimitProperties p = new RateLimitProperties();
        p.getApiKeys().put("premium-key", "premium"); // the only recognized key
        return p;
    }

    private static MockServerWebExchange request(String ip, String apiKey) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/echo")
                .remoteAddress(new java.net.InetSocketAddress(ip, 12345));
        if (apiKey != null) {
            builder.header(ClientKeyResolver.API_KEY_HEADER, apiKey);
        }
        return MockServerWebExchange.from(builder);
    }

    @Test
    void recognizedKeyGetsItsOwnBucket() {
        ClientKeyResolver resolver = new ClientKeyResolver(propsWithKnownKey());

        assertThat(resolver.resolve(request("10.0.0.1", "premium-key"))).isEqualTo("key:premium-key");
    }

    @Test
    void unknownKeyFallsBackToIpBucket() {
        ClientKeyResolver resolver = new ClientKeyResolver(propsWithKnownKey());

        // A forged/unmapped key does not earn an identity — it is counted against the caller's IP.
        assertThat(resolver.resolve(request("10.0.0.1", "totally-made-up"))).isEqualTo("ip:10.0.0.1");
    }

    @Test
    void rotatingUnknownKeysAllShareTheSameIpBucket() {
        ClientKeyResolver resolver = new ClientKeyResolver(propsWithKnownKey());

        // The bypass this guards against: different random key every request, same IP. All three must
        // resolve to one identity so they share a single bucket and cannot escape the limit.
        String a = resolver.resolve(request("203.0.113.7", "rand-1"));
        String b = resolver.resolve(request("203.0.113.7", "rand-2"));
        String c = resolver.resolve(request("203.0.113.7", "rand-3"));

        assertThat(a).isEqualTo("ip:203.0.113.7");
        assertThat(b).isEqualTo(a);
        assertThat(c).isEqualTo(a);
    }

    @Test
    void noKeyUsesIpBucket() {
        ClientKeyResolver resolver = new ClientKeyResolver(propsWithKnownKey());

        assertThat(resolver.resolve(request("198.51.100.4", null))).isEqualTo("ip:198.51.100.4");
    }
}
