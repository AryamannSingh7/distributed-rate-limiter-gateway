package com.aryamann.ratelimiter.gateway.filter;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end gateway slice: a real Spring Cloud Gateway context with the {@link RateLimitGlobalFilter}
 * in front of a {@link MockWebServer} standing in for the demo backend, and a real Redis executing the
 * Token Bucket Lua script. Proves the M2 contract: allowed requests proxy through with
 * {@code X-RateLimit-*} headers, and once the bucket drains the gateway returns {@code 429} with
 * {@code Retry-After} — without the request ever reaching the backend.
 *
 * <p>Redis comes from Testcontainers by default; set {@code REDIS_HOST} to point at an
 * already-running Redis instead (the Docker Desktop / docker-java escape hatch).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Tiered, config-driven rules: free callers get 3/window, the premium-mapped key gets 10.
                "ratelimit.default-tier=free",
                "ratelimit.tiers.free.algorithm=TOKEN_BUCKET",
                "ratelimit.tiers.free.limit=3",
                "ratelimit.tiers.free.window=60s",
                "ratelimit.tiers.premium.algorithm=TOKEN_BUCKET",
                "ratelimit.tiers.premium.limit=10",
                "ratelimit.tiers.premium.window=60s",
                "ratelimit.api-keys[premium-key]=premium",
                "ratelimit.fail-open=false"
        })
class RateLimitGatewayTest {

    private static final String BACKEND_BODY = "{\"service\":\"mock-backend\"}";

    private static final MockWebServer backend = new MockWebServer();
    private static GenericContainer<?> redisContainer;
    private static String redisHost;
    private static int redisPort;

    static {
        try {
            backend.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(BACKEND_BODY);
                }
            });
            backend.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start mock backend", e);
        }

        String envHost = System.getenv("REDIS_HOST");
        if (envHost != null && !envHost.isBlank()) {
            redisHost = envHost;
            redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        } else {
            redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            redisContainer.start();
            redisHost = redisContainer.getHost();
            redisPort = redisContainer.getMappedPort(6379);
        }
    }

    @BeforeAll
    static void flushRedis() {
        // The premium test uses a fixed API key (it must match the api-keys mapping), so its bucket
        // would otherwise persist across reruns against the shared test Redis. Start from a clean DB.
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort));
        cf.afterPropertiesSet();
        try {
            cf.getConnection().serverCommands().flushDb();
        } finally {
            cf.destroy();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> redisHost);
        registry.add("spring.data.redis.port", () -> redisPort);
        // A route with no per-route override in application.yml, so the test exercises its own tier rules.
        registry.add("spring.cloud.gateway.routes[0].id", () -> "test-route");
        registry.add("spring.cloud.gateway.routes[0].uri", () -> backend.url("/").toString());
        registry.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/api/**");
    }

    @AfterAll
    static void tearDown() throws IOException {
        backend.close();
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @Autowired
    private WebTestClient client;

    @Test
    void allowsUpToLimitThenReturns429() {
        String apiKey = "test-" + UUID.randomUUID();

        // First three requests fit in the bucket (capacity 3): proxied, with decreasing remaining.
        for (int i = 0; i < 3; i++) {
            long expectedRemaining = 2 - i;
            client.get().uri("/api/echo")
                    .header("X-API-Key", apiKey)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.service").isEqualTo("mock-backend")
                    .consumeWith(r -> {
                        HttpHeaders h = r.getResponseHeaders();
                        assertThat(h.getFirst("X-RateLimit-Limit")).isEqualTo("3");
                        assertThat(h.getFirst("X-RateLimit-Remaining")).isEqualTo(Long.toString(expectedRemaining));
                        assertThat(h.getFirst("X-RateLimit-Reset")).isNotNull();
                    });
        }

        // Fourth request: bucket empty -> 429 with Retry-After, never reaches the backend.
        client.get().uri("/api/echo")
                .header("X-API-Key", apiKey)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.error").isEqualTo("rate_limit_exceeded");
    }

    @Test
    void differentApiKeysHaveIndependentBuckets() {
        String keyA = "a-" + UUID.randomUUID();
        String keyB = "b-" + UUID.randomUUID();

        // Drain key A completely.
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/echo").header("X-API-Key", keyA)
                    .exchange().expectStatus().isOk();
        }
        client.get().uri("/api/echo").header("X-API-Key", keyA)
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Key B still has a full bucket.
        client.get().uri("/api/echo").header("X-API-Key", keyB)
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "2");
    }

    @Test
    void premiumApiKeyGetsTheHigherTierLimit() {
        // "premium-key" is mapped to the premium tier (limit 10) purely in configuration, so it must
        // sail past the free tier's limit of 3 and advertise the larger limit in the header.
        for (int i = 0; i < 4; i++) {
            long expectedRemaining = 9 - i;
            client.get().uri("/api/echo")
                    .header("X-API-Key", "premium-key")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Limit", "10")
                    .expectHeader().valueEquals("X-RateLimit-Remaining", Long.toString(expectedRemaining));
        }
    }
}
