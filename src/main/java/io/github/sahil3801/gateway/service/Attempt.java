package io.github.sahil3801.gateway.service;

/**
 * What happened when the gateway tried one provider during a request. Used for logs and for the
 * "all providers failed" error, so you can see exactly why each one was skipped or failed.
 *
 * @param provider provider name
 * @param outcome "OK", a {@link io.github.sahil3801.gateway.provider.ProviderException.Reason} name,
 *     "SKIPPED_NOT_CONFIGURED" or "SKIPPED_TIME_BUDGET"
 * @param latencyMs time spent on this provider (0 when skipped)
 */
public record Attempt(String provider, String outcome, long latencyMs) {

    static final String OK = "OK";
    static final String SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED";
    static final String SKIPPED_TIME_BUDGET = "SKIPPED_TIME_BUDGET";

    /** True if a real call was made and failed (skips don't count as fallbacks). */
    boolean calledAndFailed() {
        return !OK.equals(outcome) && !outcome.startsWith("SKIPPED_");
    }
}
