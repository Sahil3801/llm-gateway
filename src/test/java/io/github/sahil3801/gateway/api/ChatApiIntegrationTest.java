package io.github.sahil3801.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.sahil3801.gateway.support.RedisContainer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The whole application, end to end through POST /v1/chat: rate limiter -> validation -> cache ->
 * fallback chain -> provider adapters -> error handler.
 *
 * <p>One WireMock server plays all three providers (under /groq, /gemini, /cohere). Redis is a real
 * container. Each test uses its own client id and prompt, so rate limits and cache entries from one
 * test can't leak into another.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatApiIntegrationTest {

    @RegisterExtension
    static WireMockExtension providers = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String GROQ = "/groq/chat/completions";
    private static final String GEMINI = "/gemini/models/gemini-test:generateContent";
    private static final String COHERE = "/cohere/chat";
    private static final int RATE_LIMIT = 3;

    /** Points the app at WireMock and the Redis container. Overrides any real keys in the environment. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.default-provider", () -> "groq");
        registry.add("gateway.fallback.providers", () -> "gemini,cohere");
        provider(registry, "groq", "/groq", "groq-test");
        provider(registry, "gemini", "/gemini", "gemini-test");
        provider(registry, "cohere", "/cohere", "cohere-test");
        registry.add("gateway.cache.enabled", () -> "true");
        registry.add("gateway.rate-limit.enabled", () -> "true");
        registry.add("gateway.rate-limit.requests", () -> RATE_LIMIT);
        registry.add("gateway.rate-limit.window", () -> "1m");
        registry.add("spring.data.redis.host", RedisContainer::host);
        registry.add("spring.data.redis.port", RedisContainer::port);
    }

    private static void provider(DynamicPropertyRegistry registry, String name, String path, String model) {
        String prefix = "gateway.providers." + name + ".";
        registry.add(prefix + "api-key", () -> name + "-test-key");
        registry.add(prefix + "base-url", () -> providers.baseUrl() + path);
        registry.add(prefix + "model", () -> model);
        registry.add(prefix + "read-timeout", () -> "2s");
    }

    @Autowired
    private MockMvc mvc;

    private String clientId;

    @BeforeEach
    void freshClient() {
        clientId = "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void returnsNormalisedResponse() throws Exception {
        groqAnswers("Hello from Groq");

        chat("normalised response")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.provider").value("groq"))
                .andExpect(jsonPath("$.model").value("groq-test"))
                .andExpect(jsonPath("$.content").value("Hello from Groq"))
                .andExpect(jsonPath("$.finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.total_tokens").value(9))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.fallbacks").value(0))
                .andExpect(jsonPath("$.latency_ms").isNumber())
                .andExpect(header().string("X-RateLimit-Limit", String.valueOf(RATE_LIMIT)))
                .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(RATE_LIMIT - 1)));
    }

    @Test
    void rateLimitedPrimaryFallsBackToGemini() throws Exception {
        providers.stubFor(post(GROQ).willReturn(aResponse().withStatus(429)));
        geminiAnswers("Hello from Gemini");

        chat("fallback on 429")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("gemini"))
                .andExpect(jsonPath("$.content").value("Hello from Gemini"))
                .andExpect(jsonPath("$.fallbacks").value(1));
    }

    @Test
    void unknownModelOnPrimaryFallsBackInsteadOfStopping() throws Exception {
        providers.stubFor(post(GROQ).willReturn(aResponse().withStatus(404)));
        geminiAnswers("Hello from Gemini");

        chat("fallback on 404")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("gemini"));
    }

    @Test
    void allProvidersFailingGivesCleanErrorWithoutStackTrace() throws Exception {
        providers.stubFor(post(GROQ).willReturn(aResponse().withStatus(429)));
        providers.stubFor(post(GEMINI).willReturn(aResponse().withStatus(500)));
        providers.stubFor(post(COHERE).willReturn(aResponse().withStatus(503)));

        chat("everything down")
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("ALL_PROVIDERS_FAILED"))
                .andExpect(jsonPath("$.attempts[0].provider").value("groq"))
                .andExpect(jsonPath("$.attempts[0].outcome").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.attempts[1].outcome").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.attempts[2].outcome").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("test-key"))));
    }

    @Test
    void rejectedPromptIsNotSentToOtherProviders() throws Exception {
        providers.stubFor(post(GROQ).willReturn(aResponse().withStatus(400)));
        geminiAnswers("should not be used");

        chat("rejected prompt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_REJECTED_REQUEST"));
        providers.verify(0, postRequestedFor(urlEqualTo(GEMINI)));
    }

    @Test
    void secondIdenticalPromptIsServedFromCache() throws Exception {
        groqAnswers("cached answer");
        String prompt = "cache me " + UUID.randomUUID();

        chat(prompt).andExpect(jsonPath("$.cached").value(false));
        chat(prompt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.content").value("cached answer"));

        providers.verify(1, postRequestedFor(urlEqualTo(GROQ)));
    }

    @Test
    void clientOverItsLimitGets429WithRetryAfter() throws Exception {
        groqAnswers("ok");
        for (int i = 0; i < RATE_LIMIT; i++) {
            chat("limit " + i).andExpect(status().isOk());
        }

        chat("one too many")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    @Test
    void invalidRequestIsRejectedBeforeAnyProviderCall() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/v1/chat")
                        .header("X-Client-Id", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\": \"\", \"temperature\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value(containsString("prompt must not be blank")))
                .andExpect(jsonPath("$.detail").value(containsString("temperature must be between 0 and 2")));
        providers.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void malformedClientIdIsRejected() throws Exception {
        clientId = "bad id with spaces!";

        chat("bad client").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownPathIsACleanJson404() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HTTP_404"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ResultActions chat(String prompt) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/v1/chat")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\": \"" + prompt + "\"}"));
    }

    private static void groqAnswers(String text) {
        providers.stubFor(post(GROQ).willReturn(okJson("""
                {"model": "groq-test",
                 "choices": [{"message": {"content": "%s"}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 4, "completion_tokens": 5, "total_tokens": 9}}""".formatted(text))));
    }

    private static void geminiAnswers(String text) {
        providers.stubFor(post(urlPathMatching("/gemini/models/.*")).willReturn(okJson("""
                {"candidates": [{"content": {"parts": [{"text": "%s"}]}, "finishReason": "STOP"}]}""".formatted(text))));
    }
}
