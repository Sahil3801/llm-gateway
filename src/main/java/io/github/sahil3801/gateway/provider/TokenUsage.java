package io.github.sahil3801.gateway.provider;

/**
 * Token counts as reported by the provider. Fields are nullable because not every provider
 * reports every number; we never invent counts.
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    public static final TokenUsage UNKNOWN = new TokenUsage(null, null, null);
}
