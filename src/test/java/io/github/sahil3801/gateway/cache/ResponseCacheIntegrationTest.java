package io.github.sahil3801.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.TokenUsage;
import io.github.sahil3801.gateway.support.DeadRedis;
import io.github.sahil3801.gateway.support.RedisContainer;
import io.github.sahil3801.gateway.support.TestProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ResponseCache against a real Redis (Testcontainers). */
class ResponseCacheIntegrationTest {

    private static final LlmResponse ANSWER =
            new LlmResponse("groq", "m", "Redis is an in-memory store.", "stop", new TokenUsage(3, 6, 9));

    @BeforeEach
    void cleanRedis() {
        RedisContainer.flushAll();
    }

    @Test
    void storedAnswerComesBackUnchanged() {
        ResponseCache cache = cache(Duration.ofHours(1));
        assertThat(cache.get("What is Redis?")).isEmpty();

        cache.put("What is Redis?", ANSWER);

        assertThat(cache.get("What is Redis?")).contains(ANSWER);
    }

    @Test
    void keyIsTheExactPromptText() {
        ResponseCache cache = cache(Duration.ofHours(1));
        cache.put("What is Redis?", ANSWER);

        assertThat(cache.get("What is Redis? ")).isEmpty();
        assertThat(cache.get("what is redis?")).isEmpty();
    }

    @Test
    void keyIsAHashSoPromptTextNeverAppearsInRedisKeys() {
        String key = ResponseCache.key("my secret claim details");

        assertThat(key).matches("llm-gateway:cache:v1:[0-9a-f]{64}").doesNotContain("secret");
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        ResponseCache cache = cache(Duration.ofSeconds(1));
        cache.put("ttl", ANSWER);
        assertThat(cache.get("ttl")).isPresent();

        Thread.sleep(1_500);

        assertThat(cache.get("ttl")).isEmpty();
    }

    @Test
    void ttlIsSetOnTheRedisKey() {
        cache(Duration.ofHours(1)).put("ttl check", ANSWER);

        Long ttlSeconds = RedisContainer.template().getExpire(ResponseCache.key("ttl check"));
        assertThat(ttlSeconds).isBetween(3_590L, 3_600L);
    }

    @Test
    void truncatedAnswerIsNotCached() {
        ResponseCache cache = cache(Duration.ofHours(1));
        LlmResponse truncated = new LlmResponse("groq", "m", "Redis is an in-mem", "length", TokenUsage.UNKNOWN);

        cache.put("long question", truncated);

        assertThat(cache.get("long question")).isEmpty();
    }

    @Test
    void disabledCacheNeverStores() {
        GatewayProperties disabled = new GatewayProperties("groq", null,
                new GatewayProperties.Cache(false, Duration.ofHours(1)), null,
                Map.of("groq", TestProperties.provider("k", "http://unused", "m", Duration.ofSeconds(1))), 1024);
        ResponseCache cache = new ResponseCache(RedisContainer.template(), new ObjectMapper(), disabled);

        cache.put("q", ANSWER);

        assertThat(RedisContainer.template().hasKey(ResponseCache.key("q"))).isFalse();
    }

    @Test
    void redisDownBehavesLikeAMissAndNeverThrows() {
        ResponseCache cache = new ResponseCache(
                DeadRedis.template(), new ObjectMapper(), TestProperties.withCacheTtl(Duration.ofHours(1)));

        assertThatCode(() -> cache.put("q", ANSWER)).doesNotThrowAnyException();
        assertThat(cache.get("q")).isEmpty();
    }

    private static ResponseCache cache(Duration ttl) {
        return new ResponseCache(RedisContainer.template(), new ObjectMapper(), TestProperties.withCacheTtl(ttl));
    }
}
