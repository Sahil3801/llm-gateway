package io.github.sahil3801.gateway.service;

import io.github.sahil3801.gateway.api.ChatRequest;
import io.github.sahil3801.gateway.api.ChatResponse;
import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmProvider;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one chat request: build the neutral request, call a provider, time it, shape the
 * response.
 *
 * <p>Step 1: there is exactly one provider (Groq), injected directly. Step 2 replaces this with
 * config-driven provider selection, and step 3 with a fallback chain. Caching (step 4) and rate
 * limiting (step 5) also plug in around this class.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LlmProvider provider;
    private final GatewayProperties properties;

    public ChatService(LlmProvider provider, GatewayProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    public ChatResponse chat(ChatRequest request) {
        String id = UUID.randomUUID().toString();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.defaultMaxTokens();
        LlmRequest llmRequest = new LlmRequest(request.prompt(), maxTokens, request.temperature());

        long start = System.nanoTime();
        LlmResponse answer = provider.chat(llmRequest); // throws ProviderException on failure
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // Log metadata only. Prompts can contain personal data, so they are never logged.
        log.info("chat id={} provider={} model={} latencyMs={} totalTokens={}",
                id, answer.provider(), answer.model(), latencyMs, answer.usage().totalTokens());

        return ChatResponse.from(id, answer, latencyMs);
    }
}
