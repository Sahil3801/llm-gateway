package io.github.sahil3801.gateway.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache of LLM answers, keyed on the exact prompt text.
 *
 * <p><b>Key:</b> {@code llm-gateway:cache:v1:<sha256(prompt)>}. Hashing gives a fixed-length key
 * for prompts of any size and keeps raw prompt text (which may contain personal data) out of the
 * key space, where it would show up in tools like {@code redis-cli KEYS}. "Exact" is meant
 * literally: "Hello" and "hello " are different prompts. The "v1" lets a future format change
 * start from a clean cache instead of misreading old entries.
 *
 * <p><b>Value:</b> the {@link LlmResponse} as JSON, stored with a TTL (default 1 hour) so Redis
 * expires old answers by itself.
 *
 * <p><b>Redis is optional:</b> if Redis is down or slow, every method here logs a warning and
 * behaves like a cache miss. A broken cache makes the gateway slower, never broken.
 */
@Component
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);
    private static final String KEY_PREFIX = "llm-gateway:cache:v1:";
    /** Log Redis failures at most once per this interval, so an outage doesn't flood the logs. */
    private static final long WARN_INTERVAL_MS = 30_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final AtomicLong lastWarnAt = new AtomicLong();

    public ResponseCache(StringRedisTemplate redis, ObjectMapper objectMapper, GatewayProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = properties.cache().enabled();
        this.ttl = properties.cache().ttl();
        log.info("Response cache: {}", enabled ? "enabled, TTL " + ttl.toSeconds() + "s" : "disabled");
    }

    /** Returns the cached answer for this exact prompt, or empty on a miss / disabled / Redis error. */
    public Optional<LlmResponse> get(String prompt) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(prompt));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, LlmResponse.class));
        } catch (JsonProcessingException e) {
            // An entry we can't read (e.g. written by an older version): ignore it, it will be overwritten.
            log.warn("Ignoring unreadable cache entry: {}", e.getOriginalMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            warnRedisDown("read", e);
            return Optional.empty();
        }
    }

    /**
     * Stores an answer for this exact prompt.
     *
     * <p>Only complete answers ({@code finish_reason = stop}) are cached. A truncated answer (e.g.
     * max_tokens was 10) would otherwise be served to every later caller of the same prompt, even
     * ones asking for a long answer.
     */
    public void put(String prompt, LlmResponse response) {
        if (!enabled || !"stop".equals(response.finishReason())) {
            return;
        }
        try {
            redis.opsForValue().set(key(prompt), objectMapper.writeValueAsString(response), ttl);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise answer for cache: {}", e.getOriginalMessage());
        } catch (RuntimeException e) {
            warnRedisDown("write", e);
        }
    }

    static String key(String prompt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private void warnRedisDown(String operation, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("Redis cache {} failed, continuing without cache: {}", operation, e.getMessage());
        }
    }
}
