package io.github.sahil3801.gateway.provider.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for Google's native Gemini API ({@code models/{model}:generateContent}).
 *
 * <p>Gemini's format is quite different from Groq's (OpenAI-style), which is exactly why the
 * adapter pattern pays off: "contents/parts" instead of "messages", "candidates" instead of
 * "choices", camelCase usage fields, and the key in a header instead of a Bearer token. All of that
 * is converted to the same {@link LlmResponse} here, so callers never notice the difference.
 */
@Component
public class GeminiProvider implements LlmProvider {

    static final String NAME = "gemini";

    private final ProviderSettings settings;
    private final RestClient client;

    public GeminiProvider(GatewayProperties properties, RestClient.Builder builder) {
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
            throw new ProviderException(NAME, Reason.NOT_CONFIGURED, "GEMINI_API_KEY is not set");
        }

        GeminiRequest body = new GeminiRequest(
                List.of(new Content("user", List.of(new Part(request.prompt())))),
                new GenerationConfig(request.maxTokens(), request.temperature()));

        GeminiResponse response;
        try {
            response = client.post()
                    // The model is part of the URL path. {model} is filled in and URL-encoded by Spring.
                    .uri("/models/{model}:generateContent", settings.model())
                    // Key in a header, NOT in the URL (?key=...): URLs end up in access logs and
                    // error messages, headers usually don't.
                    .header("x-goog-api-key", settings.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GeminiResponse.class);
        } catch (RestClientException e) {
            throw ProviderHttp.translate(NAME, e);
        }

        return toLlmResponse(response);
    }

    private LlmResponse toLlmResponse(GeminiResponse response) {
        if (response == null) {
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE, "empty body");
        }
        // No candidates + a blockReason = Gemini's safety filter refused the prompt itself.
        if (response.candidates() == null || response.candidates().isEmpty()) {
            if (response.promptFeedback() != null && response.promptFeedback().blockReason() != null) {
                throw new ProviderException(NAME, Reason.BAD_REQUEST,
                        "prompt blocked by safety filter: " + response.promptFeedback().blockReason());
            }
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE, "response has no candidates");
        }

        Candidate candidate = response.candidates().get(0);
        // An answer can be split across several "parts"; join their text.
        String content = candidate.content() == null || candidate.content().parts() == null
                ? null
                : candidate.content().parts().stream()
                        .map(Part::text)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining());
        if (content == null || content.isBlank()) {
            // Typical cause: "thinking" models spend the whole max_tokens budget before writing.
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE,
                    "empty answer (finishReason=" + candidate.finishReason() + ")");
        }

        UsageMetadata u = response.usageMetadata();
        TokenUsage usage = u == null
                ? TokenUsage.UNKNOWN
                : new TokenUsage(u.promptTokenCount(), u.candidatesTokenCount(), u.totalTokenCount());

        String model = response.modelVersion() != null ? response.modelVersion() : settings.model();
        return new LlmResponse(NAME, model, content.strip(), normaliseFinishReason(candidate.finishReason()), usage);
    }

    /** Gemini uses UPPER_CASE reasons; map the common ones to the gateway's lowercase vocabulary. */
    private static String normaliseFinishReason(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
            default -> reason.toLowerCase(Locale.ROOT);
        };
    }

    // ---- Gemini wire format (only the fields we use) --------------------------------------------

    private record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {}

    private record Content(String role, List<Part> parts) {}

    private record Part(String text) {}

    /** Null fields are omitted so Gemini applies its own defaults. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GenerationConfig(Integer maxOutputTokens, Double temperature) {}

    private record GeminiResponse(
            List<Candidate> candidates,
            PromptFeedback promptFeedback,
            UsageMetadata usageMetadata,
            String modelVersion) {}

    private record Candidate(Content content, String finishReason) {}

    private record PromptFeedback(String blockReason) {}

    private record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount, Integer totalTokenCount) {}
}
