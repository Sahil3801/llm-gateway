package io.github.sahil3801.gateway.provider;

/**
 * The one exception type a provider adapter is allowed to throw.
 *
 * <p>Every provider fails differently (different status codes, error JSON, timeouts). Adapters
 * translate all of that into a small fixed set of {@link Reason}s, so the rest of the gateway can
 * make decisions (e.g. "try the next provider?") without knowing any provider-specific details.
 */
public class ProviderException extends RuntimeException {

    /** Why the call failed, from the gateway's point of view. */
    public enum Reason {
        /** No API key configured for this provider. */
        NOT_CONFIGURED,
        /** Provider returned 429: we are sending too much, try someone else. */
        RATE_LIMITED,
        /** Connection failed or the provider didn't answer within the timeout. */
        TIMEOUT,
        /** Provider returned 5xx: their problem, likely temporary. */
        SERVER_ERROR,
        /** Provider returned 401/403: our key is wrong or revoked (a gateway config problem). */
        AUTH_FAILED,
        /**
         * Provider returned 404: the configured model name (or base URL) doesn't exist, e.g. a typo
         * or a model the provider has retired. A gateway config problem, not a problem with the prompt.
         */
        MODEL_NOT_FOUND,
        /** Provider rejected the request itself (other 4xx), e.g. prompt too long for the model. */
        BAD_REQUEST,
        /** Provider answered 2xx but the body was empty or not in the expected format. */
        INVALID_RESPONSE
    }

    private final String provider;
    private final Reason reason;
    private final Integer httpStatus;

    public ProviderException(String provider, Reason reason, String message) {
        this(provider, reason, null, message, null);
    }

    public ProviderException(
            String provider, Reason reason, Integer httpStatus, String message, Throwable cause) {
        super("[" + provider + "] " + reason + ": " + message, cause);
        this.provider = provider;
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public String provider() {
        return provider;
    }

    public Reason reason() {
        return reason;
    }

    /** The provider's HTTP status, or {@code null} if no HTTP response was received. */
    public Integer httpStatus() {
        return httpStatus;
    }
}
