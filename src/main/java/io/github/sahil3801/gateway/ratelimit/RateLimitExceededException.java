package io.github.sahil3801.gateway.ratelimit;

/** Thrown when a client has used up its requests for the current window. Becomes a 429. */
public class RateLimitExceededException extends RuntimeException {

    private final RateLimiter.Decision decision;

    public RateLimitExceededException(RateLimiter.Decision decision) {
        super("Rate limit exceeded");
        this.decision = decision;
    }

    public RateLimiter.Decision decision() {
        return decision;
    }

    /** Seconds until the client may retry, rounded up (Retry-After takes whole seconds). */
    public long retryAfterSeconds() {
        return Math.max(1, (decision.retryAfterMs() + 999) / 1000);
    }
}
