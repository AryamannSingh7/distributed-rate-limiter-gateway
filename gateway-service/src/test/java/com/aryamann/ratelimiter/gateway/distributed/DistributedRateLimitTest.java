package com.aryamann.ratelimiter.gateway.distributed;

import com.aryamann.ratelimiter.gateway.GatewayApplication;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Distributed-correctness proof (milestone M5).
 *
 * <p>Two <b>independent</b> gateway application contexts ({@link #instanceA}, {@link #instanceB}) are
 * booted in one JVM on separate random ports, both pointing at the <b>same</b> Redis and the same
 * backend. A swarm of clients, split evenly across the two ports, hits a single shared API key at
 * once. Because the limiter's check-and-decrement runs inside one atomic Lua script in shared Redis,
 * the two instances must behave as one global bucket: across <i>both</i> of them, <b>exactly</b> the
 * configured limit is admitted — never {@code 2 × limit}, which is what per-instance (un-shared)
 * counting would produce.
 *
 * <p>The free tier here is Token Bucket with a deliberately long (1 hour) window: refill is
 * {@code limit / windowMs} tokens per ms, so a sub-second burst refills a small fraction of one
 * token and cannot admit even a single extra request. That makes the assertion an exact equality
 * rather than an approximate bound.
 *
 * <p>Redis comes from Testcontainers by default; set {@code REDIS_HOST} to reuse an already-running
 * Redis instead (the Docker Desktop / docker-java escape hatch used on Windows).
 */
class DistributedRateLimitTest {

    /** Global admission limit shared by both instances. */
    private static final int LIMIT = 50;
    /** Total simultaneous clients, split evenly across the two instances (so well above LIMIT). */
    private static final int CLIENTS = 200;

    private static final String BACKEND_BODY = "{\"service\":\"mock-backend\"}";

    private static final MockWebServer backend = new MockWebServer();
    private static GenericContainer<?> redisContainer;
    private static String redisHost;
    private static int redisPort;

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;
    private static int portA;
    private static int portB;

    @BeforeAll
    static void startTopology() throws IOException {
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

        instanceA = bootGateway();
        instanceB = bootGateway();
        portA = localPort(instanceA);
        portB = localPort(instanceB);
    }

    /** Boot a standalone gateway context against the shared Redis + backend on a random port. */
    private static ConfigurableApplicationContext bootGateway() {
        // Passed as command-line args (not .properties(), which are default/lowest precedence) so they
        // override application.yml — in particular server.port, which the yml pins to 8080.
        return new SpringApplicationBuilder(GatewayApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .run(
                        "--server.port=0",
                        "--spring.data.redis.host=" + redisHost,
                        "--spring.data.redis.port=" + redisPort,
                        "--ratelimit.enabled=true",
                        // fail closed: a limiter error must surface, not silently admit and skew the count.
                        "--ratelimit.fail-open=false",
                        "--ratelimit.default-tier=free",
                        "--ratelimit.tiers.free.algorithm=TOKEN_BUCKET",
                        "--ratelimit.tiers.free.limit=" + LIMIT,
                        // 1h window -> negligible refill during the burst, so the bound is exact.
                        "--ratelimit.tiers.free.window=1h",
                        // A route with no application.yml override, so the free-tier rule above applies as-is.
                        "--spring.cloud.gateway.routes[0].id=test-route",
                        "--spring.cloud.gateway.routes[0].uri=" + backend.url("/"),
                        "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/**");
    }

    private static int localPort(ConfigurableApplicationContext ctx) {
        return Integer.parseInt(ctx.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterAll
    static void stopTopology() throws IOException {
        if (instanceA != null) {
            instanceA.close();
        }
        if (instanceB != null) {
            instanceB.close();
        }
        backend.close();
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @Test
    void twoInstancesSharingRedisAdmitExactlyTheGlobalLimit() throws Exception {
        assertThat(portA).isNotEqualTo(portB);
        assertThat(instanceA.isRunning()).isTrue();
        assertThat(instanceB.isRunning()).isTrue();

        // Unmapped key -> free tier; the same identity on both instances folds to one Redis bucket.
        String apiKey = "dist-" + UUID.randomUUID();
        int[] ports = {portA, portB};

        HttpClient http = HttpClient.newHttpClient();
        // One thread per client: every task must reach the barrier, so the pool can't be smaller than
        // CLIENTS or the parked threads would starve the rest and ready.await() would never complete.
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CountDownLatch ready = new CountDownLatch(CLIENTS); // every client parked at the barrier
        CountDownLatch go = new CountDownLatch(1);          // released all at once for max contention

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicIntegerArray allowedPerInstance = new AtomicIntegerArray(2);

        List<Future<?>> futures = new ArrayList<>(CLIENTS);
        for (int i = 0; i < CLIENTS; i++) {
            int idx = i % 2; // alternate instances so load is genuinely split across both
            int port = ports[idx];
            Callable<Void> client = () -> {
                ready.countDown();
                go.await();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/echo"))
                        .header("X-API-Key", apiKey)
                        .GET()
                        .build();
                int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) {
                    allowed.incrementAndGet();
                    allowedPerInstance.incrementAndGet(idx);
                } else if (status == 429) {
                    blocked.incrementAndGet();
                } else {
                    other.incrementAndGet();
                }
                return null;
            };
            futures.add(pool.submit(client));
        }

        ready.await(); // wait until all clients are at the barrier
        go.countDown(); // fire
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        System.out.printf(
                "M5 distributed: allowed=%d (A=%d, B=%d) blocked=%d other=%d across %d clients on 2 instances%n",
                allowed.get(), allowedPerInstance.get(0), allowedPerInstance.get(1),
                blocked.get(), other.get(), CLIENTS);

        assertThat(other.get()).as("no unexpected statuses (fail-closed, so no silent admissions)").isZero();
        assertThat(allowed.get())
                .as("exactly the global limit admitted across BOTH instances — the shared atomic "
                        + "bucket prevents per-instance double-counting (would be up to 2x without it)")
                .isEqualTo(LIMIT);
        assertThat(allowed.get() + blocked.get())
                .as("every client got a definite allow/deny decision")
                .isEqualTo(CLIENTS);
    }
}
