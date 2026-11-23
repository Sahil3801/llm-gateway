package io.github.sahil3801.gateway.service;

import io.github.sahil3801.gateway.api.ChatRequest;
import io.github.sahil3801.gateway.api.ChatResponse;
import io.github.sahil3801.gateway.config.GatewayProperties;
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
 * <p>Which provider answers is decided by {@link FallbackExecutor} (primary first, then fallbacks).
 * This class doesn't know or care which provider it ends up being. Caching (step 4) and rate
 * limiting (step 5) plug in around it.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final FallbackExecutor fallbackExecutor;
    private final GatewayProperties properties;

    public ChatService(FallbackExecutor fallbackExecutor, GatewayProperties properties) {
        this.fallbackExecutor = fallbackExecutor;
        this.properties = properties;
    }

    public ChatResponse chat(ChatRequest request) {
        String id = UUID.randomUUID().toString();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.defaultMaxTokens();
        LlmRequest llmRequest = new LlmRequest(request.prompt(), maxTokens, request.temperature());

        long start = System.nanoTime();
        // Throws AllProvidersFailedException / ProviderException(BAD_REQUEST); the
        // GlobalExceptionHandler turns those into clean JSON errors.
        FallbackExecutor.Result result = fallbackExecutor.execute(llmRequest);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        LlmResponse answer = result.response();
        // Log metadata only. Prompts can contain personal data, so they are never logged.
        log.info("chat id={} provider={} model={} fallbacks={} latencyMs={} totalTokens={}",
                id, answer.provider(), answer.model(), result.fallbacks(), latencyMs,
                answer.usage().totalTokens());

        return ChatResponse.from(id, answer, result.fallbacks(), latencyMs);
    }
}
