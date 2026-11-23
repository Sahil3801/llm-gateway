package io.github.sahil3801.gateway.provider.groq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sahil3801.gateway.config.GatewayProperties;
import io.github.sahil3801.gateway.config.ProviderSettings;
import io.github.sahil3801.gateway.provider.LlmProvider;
import io.github.sahil3801.gateway.provider.LlmRequest;
import io.github.sahil3801.gateway.provider.LlmResponse;
import io.github.sahil3801.gateway.provider.ProviderException;
import io.github.sahil3801.gateway.provider.ProviderException.Reason;
import io.github.sahil3801.gateway.provider.ProviderHttp;
import io.github.sahil3801.gateway.provider.TokenUsage;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for Groq's Chat Completions API (OpenAI-compatible format).
 *
 * <p>Flow: {@link LlmRequest} → Groq JSON request → HTTP POST → Groq JSON response → {@link
 * LlmResponse}. All Groq-specific details (URL, JSON field names, auth header) stay inside this
 * class.
 */
@Component
public class GroqProvider implements LlmProvider {

    static final String NAME = "groq";

    private final ProviderSettings settings;
    private final RestClient client;

    public GroqProvider(GatewayProperties properties, RestClient.Builder builder) {
        this.settings = properties.provider(NAME);
        this.client = ProviderHttp.restClient(builder, settings);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return settings.hasApiKey();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (!isConfigured()) {
            throw new ProviderException(NAME, Reason.NOT_CONFIGURED, "GROQ_API_KEY is not set");
        }

        // The whole prompt goes in as a single "user" message.
        GroqRequest body = new GroqRequest(
                settings.model(),
                List.of(new GroqMessage("user", request.prompt())),
                request.maxTokens(),
                request.temperature());

        GroqResponse response;
        try {
            response = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve() // throws RestClientResponseException for any 4xx/5xx
                    .body(GroqResponse.class);
        } catch (RestClientException e) {
            throw ProviderHttp.translate(NAME, e);
        }

        return toLlmResponse(response);
    }

    /** Converts Groq's JSON into the gateway's neutral shape, rejecting empty answers. */
    private LlmResponse toLlmResponse(GroqResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE, "response has no choices");
        }
        GroqChoice choice = response.choices().get(0);
        String content = choice.message() != null ? choice.message().content() : null;
        if (content == null || content.isBlank()) {
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE, "empty answer");
        }

        GroqUsage u = response.usage();
        TokenUsage usage = u == null
                ? TokenUsage.UNKNOWN
                : new TokenUsage(u.promptTokens(), u.completionTokens(), u.totalTokens());

        // Groq echoes the model actually used; fall back to the configured one if missing.
        String model = response.model() != null ? response.model() : settings.model();
        return new LlmResponse(NAME, model, content.strip(), choice.finishReason(), usage);
    }

    // ---- Groq wire format (only the fields we use) ----------------------------------------------
    // Records are private to this adapter: no other class should depend on Groq's JSON shape.

    /** Request body. Null fields (e.g. temperature) are left out so Groq uses its own default. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GroqRequest(
            String model,
            List<GroqMessage> messages,
            @JsonProperty("max_completion_tokens") Integer maxTokens,
            Double temperature) {}

    private record GroqMessage(String role, String content) {}

    private record GroqResponse(String model, List<GroqChoice> choices, GroqUsage usage) {}

    private record GroqChoice(GroqMessage message, @JsonProperty("finish_reason") String finishReason) {}

    private record GroqUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {}
}
