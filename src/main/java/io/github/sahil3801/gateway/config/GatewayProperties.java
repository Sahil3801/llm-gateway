package io.github.sahil3801.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All gateway settings under the {@code gateway:} prefix in application.yml.
 *
 * <p>Using a typed, validated record (instead of scattered {@code @Value} strings) means a typo or
 * a missing required value fails at startup, not in the middle of a user's request.
 *
 * @param providers provider name (e.g. "groq") to its connection settings
 * @param defaultMaxTokens answer length limit used when the client doesn't send one
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @NotEmpty Map<String, @Valid ProviderSettings> providers,
        @Min(1) @Max(8192) int defaultMaxTokens) {

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
