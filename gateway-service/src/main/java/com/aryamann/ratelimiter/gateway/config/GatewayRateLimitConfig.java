package com.aryamann.ratelimiter.gateway.config;

import com.aryamann.ratelimiter.core.RateLimiter;
import com.aryamann.ratelimiter.core.RateLimiterRegistry;
import com.aryamann.ratelimiter.core.algo.FixedWindowRateLimiter;
import com.aryamann.ratelimiter.core.algo.SlidingWindowLogRateLimiter;
import com.aryamann.ratelimiter.core.algo.TokenBucketRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Wires the {@code rate-limiter-core} library into the gateway: a {@link Clock} (so the limiter's
 * time is injectable/testable), one {@link RateLimiter} bean per algorithm, and a
 * {@link RateLimiterRegistry} that indexes them so the active algorithm is chosen from configuration
 * at runtime. Adding an algorithm is one more bean here — no change to the filter.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class GatewayRateLimitConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        return new TokenBucketRateLimiter(redis, clock);
    }

    @Bean
    public FixedWindowRateLimiter fixedWindowRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        return new FixedWindowRateLimiter(redis, clock);
    }

    @Bean
    public SlidingWindowLogRateLimiter slidingWindowLogRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        return new SlidingWindowLogRateLimiter(redis, clock);
    }

    @Bean
    public RateLimiterRegistry rateLimiterRegistry(List<RateLimiter> limiters) {
        return new RateLimiterRegistry(limiters);
    }
}
