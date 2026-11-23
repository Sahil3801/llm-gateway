package io.github.sahil3801.gateway.ratelimit;

import io.github.sahil3801.gateway.config.GatewayProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-client sliding-window rate limiter backed by Redis.
 *
 * <p>"Sliding window": the limit applies to ANY rolling window, e.g. "20 requests in the last 60
 * seconds", measured from now. A fixed window ("20 per calendar minute") lets a client send 20 at
 * 12:00:59 and 20 more at 12:01:00, i.e. 40 in two seconds; a sliding window doesn't.
 *
 * <p>The logic lives in {@code redis/sliding_window.lua}, which Redis runs atomically. This class
 * only calls it and interprets the result.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String KEY_PREFIX = "llm-gateway:ratelimit:v1:";
    private static final long WARN_INTERVAL_MS = 30_000;

    /** Result of one check. {@code retryAfterMs} is 0 when allowed. */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterMs) {}

    @SuppressWarnings("rawtypes") // Redis returns the Lua table as a raw List of Longs
    private static final RedisScript<List> SCRIPT =
            new DefaultRedisScript<>(readScript(), List.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int limit;
    private final long windowMs;
    private final boolean failOpen;
    private final AtomicLong lastWarnAt = new AtomicLong();

    public RateLimiter(StringRedisTemplate redis, GatewayProperties properties) {
        GatewayProperties.RateLimit config = properties.rateLimit();
        this.redis = redis;
        this.enabled = config.enabled();
        this.limit = config.requests();
        this.windowMs = config.window().toMillis();
        this.failOpen = config.failOpen();
        log.info("Rate limit: {}", enabled
                ? limit + " requests per " + windowMs / 1000.0 + "s per client (fail-" + (failOpen ? "open" : "closed") + ")"
                : "disabled");
    }

    /** Counts this request for the client and says whether it may proceed. */
    public Decision check(String clientId) {
        if (!enabled) {
            return new Decision(true, limit, limit, 0);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SCRIPT, List.of(KEY_PREFIX + clientId),
                    String.valueOf(limit), String.valueOf(windowMs), UUID.randomUUID().toString());
            boolean allowed = result.get(0) == 1L;
            return new Decision(allowed, limit, result.get(1).intValue(), result.get(2));
        } catch (RuntimeException e) {
            // Redis down or slow. Fail-open (default): let the request through rather than take the
            // whole gateway down with Redis. Fail-closed: reject, for when cost control matters more.
            warnRedisDown(e);
            if (!failOpen) {
                throw new RateLimiterUnavailableException(e);
            }
            return new Decision(true, limit, limit, 0);
        }
    }

    private void warnRedisDown(RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("Rate limiter cannot reach Redis ({}), {} requests", e.getMessage(),
                    failOpen ? "allowing" : "rejecting");
        }
    }

    private static String readScript() {
        try {
            return new ClassPathResource("redis/sliding_window.lua").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load redis/sliding_window.lua", e);
        }
    }
}
