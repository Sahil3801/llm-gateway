package io.github.sahil3801.gateway.provider.gemini;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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

/** GeminiProvider against a fake Gemini API: its very different JSON shape maps to the same result. */
class GeminiProviderTest {

    @RegisterExtension
    static WireMockExtension gemini = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/models/gemini-test:generateContent";
    private static final LlmRequest REQUEST = new LlmRequest("Say hello", 50, 0.2);

    @Test
    void sendsNativeRequestAndJoinsAnswerParts() {
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates": [{"content": {"role": "model", "parts": [{"text": "Hello "}, {"text": "there"}]},
                                 "finishReason": "STOP"}],
                 "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 2, "totalTokenCount": 5},
                 "modelVersion": "gemini-test-001"}""")));

        LlmResponse response = provider().chat(REQUEST);

        assertThat(response.content()).isEqualTo("Hello there");
        assertThat(response.finishReason()).isEqualTo("stop"); // STOP normalised
        assertThat(response.model()).isEqualTo("gemini-test-001");
        assertThat(response.usage().promptTokens()).isEqualTo(3);
        assertThat(response.usage().completionTokens()).isEqualTo(2);

        gemini.verify(postRequestedFor(urlEqualTo(PATH)) // key NOT in the URL
                .withHeader("x-goog-api-key", equalTo("gem-key"))
                .withRequestBody(equalToJson("""
                        {"contents": [{"role": "user", "parts": [{"text": "Say hello"}]}],
                         "generationConfig": {"maxOutputTokens": 50, "temperature": 0.2}}""")));
    }

    @Test
    void safetyBlockedPromptIsARejectedRequest() {
        gemini.stubFor(post(PATH).willReturn(okJson("{\"promptFeedback\": {\"blockReason\": \"SAFETY\"}}")));

        assertReason(Reason.BAD_REQUEST);
    }

    @Test
    void thinkingModelThatRanOutOfTokensIsInvalidNotEmptyString() {
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates": [{"content": {"parts": []}, "finishReason": "MAX_TOKENS"}]}""")));

        assertReason(Reason.INVALID_RESPONSE);
    }

    @Test
    void unknownModelIsReportedAsConfigProblem() {
        gemini.stubFor(post(PATH).willReturn(aResponse().withStatus(404)));

        assertReason(Reason.MODEL_NOT_FOUND);
    }

    private void assertReason(Reason expected) {
        assertThatThrownBy(() -> provider().chat(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.reason()).isEqualTo(expected));
    }

    private static GeminiProvider provider() {
        return new GeminiProvider(
                TestProperties.withProviders(Map.of("gemini", TestProperties.provider(
                        "gem-key", gemini.baseUrl(), "gemini-test", Duration.ofSeconds(2)))),
                RestClient.builder());
    }
}
