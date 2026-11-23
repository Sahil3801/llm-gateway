package io.github.sahil3801.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All gateway settings under the {@code gateway:} prefix in application.yml.
 *
 * <p>Using a typed, validated record (instead of scattered {@code @Value} strings) means a typo or
 * a missing required value fails at startup, not in the middle of a user's request.
 *
 * @param defaultProvider the primary provider, tried first, e.g. "groq"
 * @param fallback which providers to try next when the primary fails, and the time budget
 * @param cache response cache settings
 * @param providers provider name (e.g. "groq") to its connection settings
 * @param defaultMaxTokens answer length limit used when the client doesn't send one
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @NotBlank String defaultProvider,
        @Valid Fallback fallback,
        @Valid Cache cache,
        @NotEmpty Map<String, @Valid ProviderSettings> providers,
        @Min(1) @Max(8192) int defaultMaxTokens) {

    /** No "fallback:" block in the yml means: no fallback, primary provider only. */
    public GatewayProperties {
        fallback = fallback != null ? fallback : new Fallback(null, null);
        cache = cache != null ? cache : new Cache(null, null);
    }

    /**
     * Response cache settings.
     *
     * @param enabled turn caching on/off without a code change (default: on)
     * @param ttl how long an answer stays cached before Redis expires it (default: 1 hour)
     */
    public record Cache(Boolean enabled, Duration ttl) {

        private static final Duration DEFAULT_TTL = Duration.ofHours(1);

        public Cache {
            enabled = enabled != null ? enabled : Boolean.TRUE;
            ttl = ttl != null ? ttl : DEFAULT_TTL;
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("gateway.cache.ttl must be positive, got " + ttl);
            }
        }
    }

    /**
     * Fallback settings.
     *
     * @param providers providers to try, in order, after the primary one fails (may be empty)
     * @param totalTimeout overall time budget for one request across ALL attempts. Each provider
     *     has its own read timeout; this stops 3 slow providers adding up to a 90-second request.
     */
    public record Fallback(List<String> providers, Duration totalTimeout) {

        private static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(60);

        public Fallback {
            providers = providers != null ? List.copyOf(providers) : List.of();
            totalTimeout = totalTimeout != null ? totalTimeout : DEFAULT_TOTAL_TIMEOUT;
        }
    }

    /**
     * Looks up one provider's settings, failing loudly at startup if the yml has no entry for it.
     */
    public ProviderSettings provider(String name) {
        ProviderSettings settings = providers.get(name);
        if (settings == null) {
            throw new IllegalStateException(
                    "Missing configuration block gateway.providers." + name + " in application.yml");
        }
        return settings;
    }
}
