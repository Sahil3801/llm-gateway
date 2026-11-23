package io.github.sahil3801.gateway.ratelimit;

/**
 * Redis can't be reached and the limiter is configured fail-closed, so requests are refused.
 * Becomes a 503: the client did nothing wrong, the gateway can't check its limit right now.
 */
public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(Throwable cause) {
        super("Rate limiter unavailable", cause);
    }
}
