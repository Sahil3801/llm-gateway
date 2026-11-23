package io.github.sahil3801.gateway.service;

import io.github.sahil3801.gateway.api.ChatRequest;
import io.github.sahil3801.gateway.api.ChatResponse;
import io.github.sahil3801.gateway.cache.ResponseCache;
import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one chat request:
 *
 * <ol>
 *   <li>Cache lookup on the exact prompt. Hit: answer immediately, no provider call.
 *   <li>Miss: {@link FallbackExecutor} asks providers in order (primary, then fallbacks).
 *   <li>Store the answer in the cache for the next caller.
 * </ol>
 *
 * <p>This class doesn't know or care which provider answers. Rate limiting (step 5) runs before
 * this class is reached, so cache hits still count towards a client's limit.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final FallbackExecutor fallbackExecutor;
    private final ResponseCache cache;
    private final GatewayProperties properties;

    public ChatService(FallbackExecutor fallbackExecutor, ResponseCache cache, GatewayProperties properties) {
        this.fallbackExecutor = fallbackExecutor;
        this.cache = cache;
        this.properties = properties;
    }

    public ChatResponse chat(ChatRequest request) {
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();

        // 1. Cache hit: no provider is called, so it costs no quota and returns in milliseconds.
        Optional<LlmResponse> cached = cache.get(request.prompt());
        if (cached.isPresent()) {
            long latencyMs = elapsedMs(start);
            LlmResponse answer = cached.get();
            log.info("chat id={} cache=HIT provider={} latencyMs={}", id, answer.provider(), latencyMs);
            return ChatResponse.from(id, answer, true, 0, latencyMs);
        }

        // 2. Cache miss: ask the providers.
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.defaultMaxTokens();
        LlmRequest llmRequest = new LlmRequest(request.prompt(), maxTokens, request.temperature());

        // Throws AllProvidersFailedException / ProviderException(BAD_REQUEST); the
        // GlobalExceptionHandler turns those into clean JSON errors. Failures are never cached.
        FallbackExecutor.Result result = fallbackExecutor.execute(llmRequest);
        LlmResponse answer = result.response();

        // 3. Remember the answer for the next caller with the same prompt.
        cache.put(request.prompt(), answer);
        long latencyMs = elapsedMs(start);

        // Log metadata only. Prompts can contain personal data, so they are never logged.
        log.info("chat id={} cache=MISS provider={} model={} fallbacks={} latencyMs={} totalTokens={}",
                id, answer.provider(), answer.model(), result.fallbacks(), latencyMs,
                answer.usage().totalTokens());

        return ChatResponse.from(id, answer, false, result.fallbacks(), latencyMs);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
