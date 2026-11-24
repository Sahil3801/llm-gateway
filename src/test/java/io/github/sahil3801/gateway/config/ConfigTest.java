package io.github.sahil3801.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Config records: safe defaults, invalid values rejected, and API keys never printed. */
class ConfigTest {

    @Test
    void apiKeyIsNeverPrintedByToString() {
        ProviderSettings settings = new ProviderSettings("sk-super-secret", "https://x", "m", null, null);

        assertThat(settings.toString()).doesNotContain("sk-super-secret").contains("apiKey=***");
    }

    @Test
    void missingKeyMeansNotConfigured() {
        assertThat(new ProviderSettings("", "https://x", "m", null, null).hasApiKey()).isFalse();
        assertThat(new ProviderSettings(null, "https://x", "m", null, null).hasApiKey()).isFalse();
        assertThat(new ProviderSettings("  ", "https://x", "m", null, null).hasApiKey()).isFalse();
    }

    @Test
    void missingTimeoutsGetSafeDefaultsInsteadOfWaitingForever() {
        ProviderSettings settings = new ProviderSettings("k", "https://x", "m", null, null);

        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void sectionDefaults() {
        assertThat(new GatewayProperties.Cache(null, null).ttl()).isEqualTo(Duration.ofHours(1));
        assertThat(new GatewayProperties.Cache(null, null).enabled()).isTrue();

        GatewayProperties.RateLimit rateLimit = new GatewayProperties.RateLimit(null, null, null, null);
        assertThat(rateLimit.requests()).isEqualTo(20);
        assertThat(rateLimit.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(rateLimit.failOpen()).isTrue();

        assertThat(new GatewayProperties.Fallback(null, null).providers()).isEmpty();
        assertThat(new GatewayProperties.Fallback(null, null).totalTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void invalidValuesFailAtStartup() {
        assertThatThrownBy(() -> new GatewayProperties.Cache(true, Duration.ZERO))
                .hasMessageContaining("ttl must be positive");
        assertThatThrownBy(() -> new GatewayProperties.RateLimit(true, 0, null, null))
                .hasMessageContaining("requests must be >= 1");
    }
}
