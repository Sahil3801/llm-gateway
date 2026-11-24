package io.github.sahil3801.gateway.provider.cohere;

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

/** CohereProvider against a fake Cohere v2 API. */
class CohereProviderTest {

    @RegisterExtension
    static WireMockExtension cohere = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final LlmRequest REQUEST = new LlmRequest("Say hello", 50, null);

    @Test
    void keepsOnlyTextBlocksAndMapsUsage() {
        cohere.stubFor(post("/chat").willReturn(okJson("""
                {"finish_reason": "COMPLETE",
                 "message": {"role": "assistant", "content": [
                     {"type": "thinking", "thinking": "internal reasoning"},
                     {"type": "text", "text": "Hello from Cohere"}]},
                 "usage": {"tokens": {"input_tokens": 10, "output_tokens": 4}}}""")));

        LlmResponse response = provider().chat(REQUEST);

        assertThat(response.content()).isEqualTo("Hello from Cohere"); // "thinking" block dropped
        assertThat(response.finishReason()).isEqualTo("stop");          // COMPLETE normalised
        assertThat(response.model()).isEqualTo("command-test");         // not echoed by Cohere
        assertThat(response.usage().totalTokens()).isEqualTo(14);      // computed: 10 + 4

        cohere.verify(postRequestedFor(urlEqualTo("/chat"))
                .withHeader("Authorization", equalTo("Bearer co-key"))
                .withRequestBody(equalToJson("""
                        {"model": "command-test",
                         "messages": [{"role": "user", "content": "Say hello"}],
                         "max_tokens": 50}""")));
    }

    @Test
    void serverErrorIsReported() {
        cohere.stubFor(post("/chat").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider().chat(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.SERVER_ERROR));
    }

    @Test
    void answerWithNoTextBlocksIsInvalid() {
        cohere.stubFor(post("/chat").willReturn(okJson("""
                {"finish_reason": "MAX_TOKENS",
                 "message": {"content": [{"type": "thinking", "thinking": "..."}]}}""")));

        assertThatThrownBy(() -> provider().chat(REQUEST))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    private static CohereProvider provider() {
        return new CohereProvider(
                TestProperties.withProviders(Map.of("cohere", TestProperties.provider(
                        "co-key", cohere.baseUrl(), "command-test", Duration.ofSeconds(2)))),
                RestClient.builder());
    }
}
