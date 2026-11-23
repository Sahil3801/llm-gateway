package io.github.sahil3801.gateway.provider;

/**
 * The single contract every LLM provider adapter implements (Groq, Gemini, Cohere, ...).
 *
 * <p>The rest of the gateway (controller, fallback, cache, rate limiting) only ever talks to this
 * interface. Adding a new provider means writing one new class; nothing else changes.
 */
public interface LlmProvider {

    /** Stable lowercase name used in config and in responses, e.g. "groq". */
    String name();

    /**
     * Whether this provider can be called at all (e.g. its API key is present). Unconfigured
     * providers are skipped rather than failing with a 401 on every request.
     */
    boolean isConfigured();

    /**
     * Sends the prompt to the provider and returns the normalised answer.
     *
     * @throws ProviderException for every failure (rate limit, timeout, 5xx, bad key, garbage
     *     response). Adapters must never let raw HTTP/IO exceptions escape: callers rely on {@link
     *     ProviderException.Reason} to decide what to do next.
     */
    LlmResponse chat(LlmRequest request);
}
