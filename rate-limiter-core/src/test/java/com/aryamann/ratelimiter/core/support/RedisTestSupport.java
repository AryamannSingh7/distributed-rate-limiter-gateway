package com.aryamann.ratelimiter.core.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Base for tests that need a real Redis.
 *
 * <p>By default it starts an ephemeral Redis with <b>Testcontainers</b> — that is the canonical,
 * self-contained path used in CI (Linux Docker) and is how distributed-correctness is proven.
 *
 * <p>If the environment variable {@code REDIS_HOST} is set, it instead connects to that already
 * running Redis and does not touch the Docker API at all. This is an escape hatch for environments
 * where the Testcontainers Docker client cannot reach the daemon (e.g. Docker Desktop on Windows,
 * whose named-pipe proxy can return a stub to docker-java) — there you run a Redis container
 * yourself and export {@code REDIS_HOST=localhost}.
 */
public abstract class RedisTestSupport {

    private static GenericContainer<?> redisContainer;
    private static String redisHost;
    private static int redisPort;

    protected LettuceConnectionFactory connectionFactory;
    protected ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
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

    @AfterAll
    static void stopRedis() {
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @BeforeEach
    void initTemplate() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisHost, redisPort));
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterEach
    void closeTemplate() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** Unique key per use so tests never collide and need no flush. */
    protected String randomKey() {
        return "test:" + UUID.randomUUID();
    }
}
