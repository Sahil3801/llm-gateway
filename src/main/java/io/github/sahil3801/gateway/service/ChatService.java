package io.github.sahil3801.gateway.service;

import io.github.sahil3801.gateway.api.ChatRequest;
import io.github.sahil3801.gateway.api.ChatResponse;
import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmProvider;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.ProviderRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one chat request: build the neutral request, call a provider, time it, shape the
 * response.
 *
 * <p>Step 2: the provider is chosen by config ({@code gateway.default-provider}) through the {@link
 * ProviderRegistry}. This class doesn't know or care which provider it is. Step 3 replaces the
 * single call with a fallback chain; caching (step 4) and rate limiting (step 5) plug in around it.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ProviderRegistry registry;
    private final GatewayProperties properties;

    public ChatService(ProviderRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public ChatResponse chat(ChatRequest request) {
        String id = UUID.randomUUID().toString();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.defaultMaxTokens();
        LlmRequest llmRequest = new LlmRequest(request.prompt(), maxTokens, request.temperature());

        LlmProvider provider = registry.defaultProvider();

        long start = System.nanoTime();
        LlmResponse answer = provider.chat(llmRequest); // throws ProviderException on failure
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // Log metadata only. Prompts can contain personal data, so they are never logged.
        log.info("chat id={} provider={} model={} latencyMs={} totalTokens={}",
                id, answer.provider(), answer.model(), latencyMs, answer.usage().totalTokens());

        return ChatResponse.from(id, answer, latencyMs);
    }
}
