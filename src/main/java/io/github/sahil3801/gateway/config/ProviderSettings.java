package io.github.sahil3801.gateway.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;

/**
 * Connection settings for one LLM provider, bound from {@code gateway.providers.<name>} in
 * application.yml.
 *
 * <p>The API key itself is never written in the yml: the yml only says {@code ${GROQ_API_KEY:}},
 * so the value comes from the environment at runtime. An empty value means "not configured".
 *
 * @param apiKey secret key, read from an environment variable (may be blank)
 * @param baseUrl provider API root, e.g. https://api.groq.com/openai/v1
 * @param model model name sent to the provider; configurable because free models change often
 * @param connectTimeout max time to open the TCP/TLS connection
 * @param readTimeout max time to wait for the provider's answer
 */
public record ProviderSettings(
        String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String model,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    /** Fills in safe defaults so a missing timeout never means "wait forever". */
    public ProviderSettings {
        connectTimeout = connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        readTimeout = readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT;
    }

    /** A provider without a key is skipped instead of failing every request with 401. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Records print all fields in toString() by default. Overridden so the API key can never end up
     * in a log line or an error message by accident.
     */
    @Override
    public String toString() {
        return "ProviderSettings[baseUrl=%s, model=%s, apiKey=%s, connectTimeout=%s, readTimeout=%s]"
                .formatted(baseUrl, model, hasApiKey() ? "***" : "<missing>", connectTimeout, readTimeout);
    }
}
