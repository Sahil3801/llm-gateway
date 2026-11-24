package io.github.sahil3801.gateway.provider.groq;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.ProviderException;
import io.github.sahil3801.gateway.provider.ProviderException.Reason;
import io.github.sahil3801.gateway.support.TestProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

/** GroqProvider against a fake Groq (WireMock): request format, response mapping, error mapping. */
class GroqProviderTest {

    @RegisterExtension
    static WireMockExtension groq = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final LlmRequest REQUEST = new LlmRequest("Say hello", 50, null);

    @Test
    void sendsOpenAiStyleRequestAndMapsTheAnswer() {
        groq.stubFor(post("/chat/completions").willReturn(okJson("""
                {"model": "llama-test",
                 "choices": [{"message": {"role": "assistant", "content": "  Hello!  "}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}}""")));

        LlmResponse response = provider("secret-key").chat(REQUEST);

        assertThat(response.provider()).isEqualTo("groq");
        assertThat(response.model()).isEqualTo("llama-test");
        assertThat(response.content()).isEqualTo("Hello!"); // whitespace stripped
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().totalTokens()).isEqualTo(7);

        groq.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer secret-key"))
                .withRequestBody(equalToJson("""
                        {"model": "configured-model",
                         "messages": [{"role": "user", "content": "Say hello"}],
                         "max_completion_tokens": 50}"""))
                // null temperature is left out so Groq uses its own default
                .withRequestBody(notContaining("temperature")));
    }

    @Test
    void rateLimitIsReported() {
        groq.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(429)));

        assertReason(Reason.RATE_LIMITED);
    }

    @Test
    void unknownModelIsReportedAsConfigProblem() {
        groq.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":{\"code\":\"model_not_found\"}}")));

        assertReason(Reason.MODEL_NOT_FOUND);
    }

    @Test
    void slowProviderTimesOut() {
        groq.stubFor(post("/chat/completions").willReturn(okJson("{}").withFixedDelay(1_500)));

        assertReason(Reason.TIMEOUT); // read timeout in provider() is 500 ms
    }

    @Test
    void emptyAnswerIsInvalid() {
        groq.stubFor(post("/chat/completions").willReturn(okJson("{\"choices\": []}")));

        assertReason(Reason.INVALID_RESPONSE);
    }

    @Test
    void withoutKeyNoRequestIsSent() {
        assertThatThrownBy(() -> provider("").chat(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
        groq.verify(0, anyRequestedFor(anyUrl()));
    }

    private void assertReason(Reason expected) {
        assertThatThrownBy(() -> provider("k").chat(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.reason()).isEqualTo(expected));
    }

    private static GroqProvider provider(String apiKey) {
        return new GroqProvider(
                TestProperties.withProviders(Map.of("groq", TestProperties.provider(
                        apiKey, groq.baseUrl(), "configured-model", Duration.ofMillis(500)))),
                RestClient.builder());
    }
}
