package io.github.sahil3801.gateway.provider;

/**
 * The provider-neutral request every {@link LlmProvider} receives.
 *
 * <p>Deliberately separate from the public API DTO: the HTTP contract can evolve (new optional
 * fields, renamed JSON keys) without touching every provider adapter.
 *
 * @param prompt the user's prompt text
 * @param maxTokens maximum length of the answer, in tokens
 * @param temperature randomness, 0.0–2.0; {@code null} means "use the provider's default"
 */
public record LlmRequest(String prompt, int maxTokens, Double temperature) {}
