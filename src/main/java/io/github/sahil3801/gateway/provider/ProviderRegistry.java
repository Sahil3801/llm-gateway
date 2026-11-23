package io.github.sahil3801.gateway.provider;

import io.github.sahil3801.gateway.config.GatewayProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Knows every {@link LlmProvider} in the application and finds one by name.
 *
 * <p>Spring injects all beans implementing {@code LlmProvider} as a list, so a new adapter class is
 * picked up automatically: no registration code, no if/else chain on provider names.
 *
 * <p>The selected provider is checked once, at startup. A typo like {@code LLM_PROVIDER=gemni} stops
 * the app immediately with a clear message, instead of failing every request later.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, LlmProvider> providersByName;
    private final LlmProvider defaultProvider;

    public ProviderRegistry(List<LlmProvider> providers, GatewayProperties properties) {
        Map<String, LlmProvider> byName = new LinkedHashMap<>();
        for (LlmProvider provider : providers) {
            LlmProvider previous = byName.put(provider.name(), provider);
            if (previous != null) {
                throw new IllegalStateException("Two providers share the name '" + provider.name() + "'");
            }
        }
        this.providersByName = Collections.unmodifiableMap(byName);
        this.defaultProvider = get(properties.defaultProvider());

        // One startup line showing what is usable, e.g. "groq=ready, gemini=no API key, ...".
        String status = byName.values().stream()
                .map(p -> p.name() + "=" + (p.isConfigured() ? "ready" : "no API key"))
                .collect(Collectors.joining(", "));
        log.info("LLM providers: {}. Selected provider: {}", status, defaultProvider.name());
        if (!defaultProvider.isConfigured()) {
            log.warn("Selected provider '{}' has no API key; requests will fail until it is set.",
                    defaultProvider.name());
        }
    }

    /** The provider chosen in config ({@code gateway.default-provider}). */
    public LlmProvider defaultProvider() {
        return defaultProvider;
    }

    /**
     * Finds a provider by name.
     *
     * @throws IllegalStateException if no provider has that name (lists the valid names)
     */
    public LlmProvider get(String name) {
        LlmProvider provider = providersByName.get(name);
        if (provider == null) {
            throw new IllegalStateException(
                    "Unknown LLM provider '" + name + "'. Valid names: " + providersByName.keySet());
        }
        return provider;
    }

    /** All providers, in registration order. */
    public List<LlmProvider> all() {
        return List.copyOf(providersByName.values());
    }
}
