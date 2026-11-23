package io.github.sahil3801.gateway.provider;

import io.github.sahil3801.gateway.config.GatewayProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Knows every {@link LlmProvider} in the application and the order in which to try them.
 *
 * <p>Spring injects all beans implementing {@code LlmProvider} as a list, so a new adapter class is
 * picked up automatically: no registration code, no if/else chain on provider names.
 *
 * <p>The chain is built and checked once, at startup: primary provider first, then the fallback
 * list. A typo like {@code LLM_PROVIDER=gemni} stops the app immediately with a clear message,
 * instead of failing every request later.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, LlmProvider> providersByName;
    private final List<LlmProvider> chain;

    public ProviderRegistry(List<LlmProvider> providers, GatewayProperties properties) {
        Map<String, LlmProvider> byName = new LinkedHashMap<>();
        for (LlmProvider provider : providers) {
            LlmProvider previous = byName.put(provider.name(), provider);
            if (previous != null) {
                throw new IllegalStateException("Two providers share the name '" + provider.name() + "'");
            }
        }
        this.providersByName = Collections.unmodifiableMap(byName);
        this.chain = buildChain(properties);

        // Startup summary, e.g. "groq=ready, gemini=no API key" and "groq -> gemini -> cohere".
        String status = byName.values().stream()
                .map(p -> p.name() + "=" + (p.isConfigured() ? "ready" : "no API key"))
                .collect(Collectors.joining(", "));
        String order = chain.stream().map(LlmProvider::name).collect(Collectors.joining(" -> "));
        log.info("LLM providers: {}. Fallback chain: {}", status, order);
        if (chain.stream().noneMatch(LlmProvider::isConfigured)) {
            log.warn("No provider in the chain has an API key; every request will fail until one is set.");
        }
    }

    /**
     * Primary provider first, then the fallback providers in configured order. Duplicates are
     * dropped (e.g. primary "groq" also listed as a fallback), so no provider is tried twice.
     */
    private List<LlmProvider> buildChain(GatewayProperties properties) {
        Set<String> names = new LinkedHashSet<>();
        names.add(properties.defaultProvider().trim());
        for (String name : properties.fallback().providers()) {
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        List<LlmProvider> result = new ArrayList<>();
        for (String name : names) {
            result.add(get(name)); // throws on an unknown name -> app fails to start
        }
        return List.copyOf(result);
    }

    /** Providers in the order they should be tried. Never empty. */
    public List<LlmProvider> chain() {
        return chain;
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
}
