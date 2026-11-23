package io.github.sahil3801.gateway.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when every provider in the chain was tried (or skipped) and none produced an answer.
 * Carries the per-provider attempts so the error response and the log can explain why.
 */
public class AllProvidersFailedException extends RuntimeException {

    private final List<Attempt> attempts;

    public AllProvidersFailedException(List<Attempt> attempts) {
        super("All LLM providers failed: " + attempts.stream()
                .map(a -> a.provider() + "=" + a.outcome())
                .collect(Collectors.joining(", ")));
        this.attempts = List.copyOf(attempts);
    }

    public List<Attempt> attempts() {
        return attempts;
    }

    /** True when no provider was even callable (no API keys at all): a setup problem, not an outage. */
    public boolean noneConfigured() {
        return attempts.stream().allMatch(a -> Attempt.SKIPPED_NOT_CONFIGURED.equals(a.outcome()));
    }
}
