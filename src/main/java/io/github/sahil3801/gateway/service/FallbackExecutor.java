package io.github.sahil3801.gateway.service;

import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmProvider;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.ProviderException;
import io.github.sahil3801.gateway.provider.ProviderException.Reason;
import io.github.sahil3801.gateway.provider.ProviderRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tries the providers in chain order until one answers.
 *
 * <p>Decision per failure reason:
 *
 * <ul>
 *   <li>RATE_LIMITED, TIMEOUT, SERVER_ERROR, INVALID_RESPONSE: provider-side problems, so the next
 *       provider will probably succeed. Move on.
 *   <li>AUTH_FAILED: our key for THIS provider is wrong. Another provider has its own key. Move on,
 *       but log it as an error because a human needs to fix the config.
 *   <li>BAD_REQUEST: the provider rejected the request itself (e.g. safety filter, prompt too long).
 *       Stop. Retrying elsewhere would mostly fail the same way, burn quota on every provider, and
 *       "shop around" a prompt one provider deliberately refused.
 *   <li>Not configured (no API key): skipped without a call.
 * </ul>
 *
 * <p>A total time budget stops the chain from adding up several slow providers into one very long
 * request: once it is used up, the remaining providers are skipped.
 */
@Component
public class FallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackExecutor.class);

    private final ProviderRegistry registry;
    private final Duration totalTimeout;

    public FallbackExecutor(ProviderRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.totalTimeout = properties.fallback().totalTimeout();
    }

    /** The successful answer plus the full attempt history (failed providers first). */
    public record Result(LlmResponse response, List<Attempt> attempts) {

        /** How many providers were called and failed before one succeeded. */
        public int fallbacks() {
            return (int) attempts.stream().filter(Attempt::calledAndFailed).count();
        }
    }

    /**
     * Runs the request through the chain.
     *
     * @throws ProviderException with reason BAD_REQUEST if a provider rejected the request itself
     * @throws AllProvidersFailedException if no provider produced an answer
     */
    public Result execute(LlmRequest request) {
        List<Attempt> attempts = new ArrayList<>();
        long chainStart = System.nanoTime();

        for (LlmProvider provider : registry.chain()) {
            if (!provider.isConfigured()) {
                attempts.add(new Attempt(provider.name(), Attempt.SKIPPED_NOT_CONFIGURED, 0));
                continue;
            }
            // Don't start a new attempt once the budget is spent. (A call already in progress is
            // still bounded by that provider's own read timeout.)
            if (elapsedMs(chainStart) >= totalTimeout.toMillis()) {
                attempts.add(new Attempt(provider.name(), Attempt.SKIPPED_TIME_BUDGET, 0));
                continue;
            }

            long start = System.nanoTime();
            try {
                LlmResponse response = provider.chat(request);
                attempts.add(new Attempt(provider.name(), Attempt.OK, elapsedMs(start)));
                return new Result(response, attempts);
            } catch (ProviderException e) {
                long latency = elapsedMs(start);
                attempts.add(new Attempt(provider.name(), e.reason().name(), latency));

                if (e.reason() == Reason.BAD_REQUEST) {
                    log.warn("Provider {} rejected the request ({}); not trying other providers",
                            provider.name(), e.getMessage());
                    throw e;
                }
                if (e.reason() == Reason.AUTH_FAILED) {
                    log.error("Provider {} rejected our API key (HTTP {}); check its *_API_KEY. Trying next provider.",
                            provider.name(), e.httpStatus());
                } else {
                    log.warn("Provider {} failed ({}) after {} ms; trying next provider",
                            provider.name(), e.reason(), latency);
                }
            }
        }

        throw new AllProvidersFailedException(attempts);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
