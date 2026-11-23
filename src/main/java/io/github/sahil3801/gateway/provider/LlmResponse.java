package io.github.sahil3801.gateway.provider;

/**
 * The provider-neutral answer. Every adapter converts its provider's own JSON into this shape, which
 * is what lets the REST API return the same response format regardless of who answered.
 *
 * @param provider which provider answered, e.g. "groq"
 * @param model the exact model that produced the answer
 * @param content the generated text
 * @param finishReason why generation stopped, normalised to lowercase (e.g. "stop", "length")
 * @param usage token counts
 */
public record LlmResponse(
        String provider, String model, String content, String finishReason, TokenUsage usage) {}
