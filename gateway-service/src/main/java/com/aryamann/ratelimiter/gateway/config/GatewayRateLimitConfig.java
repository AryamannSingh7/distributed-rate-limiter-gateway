package com.aryamann.ratelimiter.gateway.config;

import com.aryamann.ratelimiter.core.Algorithm;
import com.aryamann.ratelimiter.core.RateLimiter;
import com.aryamann.ratelimiter.core.algo.TokenBucketRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;

/**
 * Wires the {@code rate-limiter-core} library into the gateway: a {@link Clock} (so the limiter's
 * time is injectable/testable) and the active {@link RateLimiter}.
 *
 * <p>M2 ships only the Token Bucket limiter. M3 turns this into a registry keyed by
 * {@link Algorithm} so the algorithm can be selected per rule at runtime.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class GatewayRateLimitConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RateLimiter rateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        return new TokenBucketRateLimiter(redis, clock);
    }
}
