package com.aryamann.ratelimiter.core.algo;

import com.aryamann.ratelimiter.core.RateLimitResult;
import com.aryamann.ratelimiter.core.RateLimiter;
import com.aryamann.ratelimiter.core.RuleConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Base for Lua-backed rate limiters. The whole read-decide-write happens inside a single Lua script,
 * which Redis executes atomically on its single thread — so check-and-decrement is race-free across
 * every gateway instance sharing the Redis. That atomicity is the entire point of doing this in Lua
 * rather than with separate GET/SET round-trips.
 *
 * <p>Current time is taken from an injected {@link Clock} and passed into the script as an argument
 * (the scripts never call Redis {@code TIME}). This keeps the algorithms deterministic and unit
 * testable — a test can advance a fake clock to exercise refill / window-roll behaviour.
 */
public abstract class AbstractLuaRateLimiter implements RateLimiter {

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final RedisScript<List> script;

    protected AbstractLuaRateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(scriptLocation()));
        s.setResultType(List.class);
        this.script = s;
    }

    /** Classpath location of this algorithm's Lua script, e.g. {@code lua/token_bucket.lua}. */
    protected abstract String scriptLocation();

    /** Redis KEYS for the given bucket key (most algorithms use a single key). */
    protected abstract List<String> keys(String key);

    /** Lua ARGV for this rule at time {@code nowMs}. All values are passed as strings; Lua parses them. */
    protected abstract List<String> argv(RuleConfig rule, long nowMs);

    /**
     * Map the script's return array into a {@link RateLimitResult}. Every algorithm's script returns
     * the same 4-tuple {@code {allowed(0|1), remaining, retryAfterMs, resetAfterMs}}; the limit is
     * taken from the rule. Overridable should an algorithm ever need a different shape.
     */
    protected RateLimitResult toResult(RuleConfig rule, List<Long> raw) {
        boolean allowed = raw.get(0) == 1L;
        long remaining = raw.get(1);
        long retryAfterMs = raw.get(2);
        long resetAfterMs = raw.get(3);
        return new RateLimitResult(allowed, rule.limit(), remaining, retryAfterMs, resetAfterMs);
    }

    @Override
    public Mono<RateLimitResult> tryAcquire(String key, RuleConfig rule) {
        long nowMs = clock.millis();
        return redis.execute(script, keys(key), argv(rule, nowMs))
                .next()
                .map(this::toLongList)
                .map(raw -> toResult(rule, raw));
    }

    /**
     * Normalise the script's array reply to {@code Long}. Depending on client/serializer, integer
     * replies may arrive as {@link Number}, raw {@code byte[]}, or {@code String} — handle all.
     */
    private List<Long> toLongList(Object raw) {
        List<?> list = (List<?>) raw;
        List<Long> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.longValue());
            } else if (o instanceof byte[] b) {
                out.add(Long.parseLong(new String(b, StandardCharsets.UTF_8).trim()));
            } else {
                out.add(Long.parseLong(String.valueOf(o).trim()));
            }
        }
        return out;
    }
}
