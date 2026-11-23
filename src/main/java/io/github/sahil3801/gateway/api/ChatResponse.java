package io.github.sahil3801.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.TokenUsage;

/**
 * The normalised response of {@code POST /v1/chat}. The shape is identical whichever provider
 * answered; only the values of {@code provider} and {@code model} tell you who it was, and
 * {@code fallbacks} tells you how many providers failed before it. {@code cached: true} means the
 * answer came from the cache and no provider was called for this request.
 *
 * <pre>
 * {
 *   "id": "4f0c...",
 *   "provider": "groq",
 *   "model": "openai/gpt-oss-20b",
 *   "content": "The claim concerns ...",
 *   "finish_reason": "stop",
 *   "usage": {"prompt_tokens": 42, "completion_tokens": 88, "total_tokens": 130},
 *   "latency_ms": 412
 * }
 * </pre>
 */
public record ChatResponse(
        String id,
        String provider,
        String model,
        String content,
        @JsonProperty("finish_reason") String finishReason,
        Usage usage,
        boolean cached,
        int fallbacks,
        @JsonProperty("latency_ms") long latencyMs) {

    /** Token counts; a field is null when the provider didn't report it. */
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {

        static Usage from(TokenUsage u) {
            return new Usage(u.promptTokens(), u.completionTokens(), u.totalTokens());
        }
    }

    /** Maps the internal provider-neutral answer to the public JSON shape. */
    public static ChatResponse from(String id, LlmResponse r, boolean cached, int fallbacks, long latencyMs) {
        return new ChatResponse(id, r.provider(), r.model(), r.content(), r.finishReason(),
                Usage.from(r.usage()), cached, fallbacks, latencyMs);
    }
}
