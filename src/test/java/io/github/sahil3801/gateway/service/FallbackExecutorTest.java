package io.github.sahil3801.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.provider.LlmProvider;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.ProviderException;
import io.github.sahil3801.gateway.provider.ProviderException.Reason;
import io.github.sahil3801.gateway.provider.ProviderRegistry;
import io.github.sahil3801.gateway.provider.TokenUsage;
import io.github.sahil3801.gateway.support.TestProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The fallback rules, tested with in-memory fake providers: no HTTP, no Spring, millisecond-fast.
 * Each fake either answers or throws a chosen {@link Reason}, and counts how often it was called.
 */
class FallbackExecutorTest {

    private static final LlmRequest REQUEST = new LlmRequest("hi", 100, null);

    @Test
    void healthyPrimaryAnswersAndOthersAreNeverCalled() {
        FakeProvider groq = FakeProvider.answering("groq");
        FakeProvider gemini = FakeProvider.answering("gemini");

        FallbackExecutor.Result result = executor(List.of(groq, gemini), "groq", List.of("gemini")).execute(REQUEST);

        assertThat(result.response().provider()).isEqualTo("groq");
        assertThat(result.fallbacks()).isZero();
        assertThat(gemini.calls).isZero();
    }

    /** Provider-side or provider-config problems: the next provider should be tried. */
    @ParameterizedTest(name = "{0} -> try next provider")
    @EnumSource(value = Reason.class,
            names = {"RATE_LIMITED", "TIMEOUT", "SERVER_ERROR", "INVALID_RESPONSE", "AUTH_FAILED", "MODEL_NOT_FOUND"})
    void failureMovesToNextProvider(Reason reason) {
        FakeProvider groq = FakeProvider.failing("groq", reason);
        FakeProvider gemini = FakeProvider.answering("gemini");

        FallbackExecutor.Result result = executor(List.of(groq, gemini), "groq", List.of("gemini")).execute(REQUEST);

        assertThat(result.response().provider()).isEqualTo("gemini");
        assertThat(result.fallbacks()).isEqualTo(1);
        assertThat(result.attempts()).extracting(Attempt::outcome).containsExactly(reason.name(), "OK");
    }

    @Test
    void rejectedPromptStopsTheChain() {
        FakeProvider groq = FakeProvider.failing("groq", Reason.BAD_REQUEST);
        FakeProvider gemini = FakeProvider.answering("gemini");

        assertThatThrownBy(() -> executor(List.of(groq, gemini), "groq", List.of("gemini")).execute(REQUEST))
                .isInstanceOf(ProviderException.class)
                .extracting(e -> ((ProviderException) e).reason())
                .isEqualTo(Reason.BAD_REQUEST);
        assertThat(gemini.calls).as("a rejected prompt must not be sent to other providers").isZero();
    }

    @Test
    void providerWithoutKeyIsSkippedWithoutACall() {
        FakeProvider groq = FakeProvider.unconfigured("groq");
        FakeProvider gemini = FakeProvider.answering("gemini");

        FallbackExecutor.Result result = executor(List.of(groq, gemini), "groq", List.of("gemini")).execute(REQUEST);

        assertThat(groq.calls).isZero();
        assertThat(result.response().provider()).isEqualTo("gemini");
        assertThat(result.fallbacks()).as("a skip is not a failed call").isZero();
    }

    @Test
    void allFailingListsEveryAttempt() {
        List<LlmProvider> chain = List.of(
                FakeProvider.failing("groq", Reason.RATE_LIMITED),
                FakeProvider.failing("gemini", Reason.SERVER_ERROR),
                FakeProvider.failing("cohere", Reason.TIMEOUT));

        assertThatThrownBy(() -> executor(chain, "groq", List.of("gemini", "cohere")).execute(REQUEST))
                .isInstanceOfSatisfying(AllProvidersFailedException.class, e -> {
                    assertThat(e.attempts()).extracting(Attempt::provider).containsExactly("groq", "gemini", "cohere");
                    assertThat(e.attempts()).extracting(Attempt::outcome)
                            .containsExactly("RATE_LIMITED", "SERVER_ERROR", "TIMEOUT");
                    assertThat(e.noneConfigured()).isFalse();
                });
    }

    @Test
    void noKeysAnywhereIsReportedAsNoneConfigured() {
        List<LlmProvider> chain = List.of(FakeProvider.unconfigured("groq"), FakeProvider.unconfigured("gemini"));

        assertThatThrownBy(() -> executor(chain, "groq", List.of("gemini")).execute(REQUEST))
                .isInstanceOfSatisfying(AllProvidersFailedException.class,
                        e -> assertThat(e.noneConfigured()).isTrue());
    }

    @Test
    void spentTimeBudgetSkipsRemainingProviders() {
        FakeProvider slow = FakeProvider.slowThenFailing("groq", 150, Reason.TIMEOUT);
        FakeProvider gemini = FakeProvider.answering("gemini");

        assertThatThrownBy(() -> executor(List.of(slow, gemini), "groq", List.of("gemini"), Duration.ofMillis(100))
                        .execute(REQUEST))
                .isInstanceOfSatisfying(AllProvidersFailedException.class, e -> assertThat(e.attempts())
                        .extracting(Attempt::outcome).containsExactly("TIMEOUT", "SKIPPED_TIME_BUDGET"));
        assertThat(gemini.calls).isZero();
    }

    @Test
    void duplicateInFallbackListIsTriedOnlyOnce() {
        FakeProvider groq = FakeProvider.failing("groq", Reason.SERVER_ERROR);
        FakeProvider gemini = FakeProvider.answering("gemini");

        executor(List.of(groq, gemini), "groq", List.of("groq", "gemini")).execute(REQUEST);

        assertThat(groq.calls).isEqualTo(1);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static FallbackExecutor executor(List<LlmProvider> providers, String primary, List<String> fallbacks) {
        return executor(providers, primary, fallbacks, Duration.ofSeconds(60));
    }

    private static FallbackExecutor executor(
            List<LlmProvider> providers, String primary, List<String> fallbacks, Duration budget) {
        GatewayProperties properties = TestProperties.withChain(primary, fallbacks, budget);
        return new FallbackExecutor(new ProviderRegistry(providers, properties), properties);
    }

    /** A provider whose behaviour is fixed up front. */
    private static final class FakeProvider implements LlmProvider {
        private final String name;
        private final boolean configured;
        private final Reason failure; // null = answer successfully
        private final long delayMs;
        int calls;

        private FakeProvider(String name, boolean configured, Reason failure, long delayMs) {
            this.name = name;
            this.configured = configured;
            this.failure = failure;
            this.delayMs = delayMs;
        }

        static FakeProvider answering(String name) {
            return new FakeProvider(name, true, null, 0);
        }

        static FakeProvider failing(String name, Reason reason) {
            return new FakeProvider(name, true, reason, 0);
        }

        static FakeProvider slowThenFailing(String name, long delayMs, Reason reason) {
            return new FakeProvider(name, true, reason, delayMs);
        }

        static FakeProvider unconfigured(String name) {
            return new FakeProvider(name, false, null, 0);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls++;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw new ProviderException(name, failure, "simulated");
            }
            return new LlmResponse(name, "model", "answer from " + name, "stop", TokenUsage.UNKNOWN);
        }
    }
}
