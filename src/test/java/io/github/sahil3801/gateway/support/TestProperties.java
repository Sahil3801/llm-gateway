package io.github.sahil3801.gateway.support;

import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.config.ProviderSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link GatewayProperties} in plain unit tests, without starting Spring. Keeps each test
 * focused on the one setting it cares about.
 */
public final class TestProperties {

    private TestProperties() {}

    /** Settings for one provider pointing at a (usually WireMock) base URL. */
    public static ProviderSettings provider(String apiKey, String baseUrl, String model, Duration readTimeout) {
        return new ProviderSettings(apiKey, baseUrl, model, Duration.ofSeconds(2), readTimeout);
    }

    /** Properties with just the given providers; everything else uses its defaults. */
    public static GatewayProperties withProviders(Map<String, ProviderSettings> providers) {
        return new GatewayProperties(
                providers.keySet().iterator().next(), null, null, null, providers, 1024);
    }

    /** Properties describing a fallback chain: {@code primary} first, then {@code fallbacks}. */
    public static GatewayProperties withChain(String primary, List<String> fallbacks, Duration totalTimeout) {
        ProviderSettings dummy = provider("k", "http://unused", "m", Duration.ofSeconds(1));
        return new GatewayProperties(
                primary,
                new GatewayProperties.Fallback(fallbacks, totalTimeout),
                null,
                null,
                Map.of(primary, dummy),
                1024);
    }

    /** Properties with a rate limit; Redis-related tests use this. */
    public static GatewayProperties withRateLimit(int requests, Duration window, boolean failOpen) {
        return new GatewayProperties(
                "groq", null, null,
                new GatewayProperties.RateLimit(true, requests, window, failOpen),
                Map.of("groq", provider("k", "http://unused", "m", Duration.ofSeconds(1))),
                1024);
    }

    /** Properties with a cache TTL; Redis-related tests use this. */
    public static GatewayProperties withCacheTtl(Duration ttl) {
        return new GatewayProperties(
                "groq", null,
                new GatewayProperties.Cache(true, ttl),
                null,
                Map.of("groq", provider("k", "http://unused", "m", Duration.ofSeconds(1))),
                1024);
    }
}
