package io.github.sahil3801.gateway.provider.cohere;

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
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for Cohere's v2 Chat API ({@code POST /v2/chat}).
 *
 * <p>Differences from the others: the answer is a list of typed content blocks (we keep only
 * "text" blocks), finish reasons are UPPER_CASE ("COMPLETE"), usage is nested under "tokens", and
 * the response does not echo the model name.
 */
@Component
public class CohereProvider implements LlmProvider {

    static final String NAME = "cohere";

    private final ProviderSettings settings;
    private final RestClient client;

    public CohereProvider(GatewayProperties properties, RestClient.Builder builder) {
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
            throw new ProviderException(NAME, Reason.NOT_CONFIGURED, "COHERE_API_KEY is not set");
        }

        CohereRequest body = new CohereRequest(
                settings.model(),
                List.of(new RequestMessage("user", request.prompt())),
                request.maxTokens(),
                request.temperature());

        CohereResponse response;
        try {
            response = client.post()
                    .uri("/chat")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(CohereResponse.class);
        } catch (RestClientException e) {
            throw ProviderHttp.translate(NAME, e);
        }

        return toLlmResponse(response);
    }

    private LlmResponse toLlmResponse(CohereResponse response) {
        if (response == null || response.message() == null || response.message().content() == null) {
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE, "response has no message");
        }
        // Keep only "text" blocks; reasoning models may also return e.g. "thinking" blocks.
        String content = response.message().content().stream()
                .filter(block -> "text".equals(block.type()) && block.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining());
        if (content.isBlank()) {
            throw new ProviderException(NAME, Reason.INVALID_RESPONSE,
                    "empty answer (finish_reason=" + response.finishReason() + ")");
        }

        TokenUsage usage = TokenUsage.UNKNOWN;
        if (response.usage() != null && response.usage().tokens() != null) {
            Tokens t = response.usage().tokens();
            Integer total = t.inputTokens() != null && t.outputTokens() != null
                    ? t.inputTokens() + t.outputTokens()
                    : null;
            usage = new TokenUsage(t.inputTokens(), t.outputTokens(), total);
        }

        // Cohere doesn't echo the model, so report the one we asked for.
        return new LlmResponse(NAME, settings.model(), content.strip(),
                normaliseFinishReason(response.finishReason()), usage);
    }

    /** Maps Cohere's reasons to the gateway's lowercase vocabulary. */
    private static String normaliseFinishReason(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "COMPLETE", "STOP_SEQUENCE" -> "stop";
            case "MAX_TOKENS" -> "length";
            default -> reason.toLowerCase(Locale.ROOT);
        };
    }

    // ---- Cohere wire format (only the fields we use) --------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CohereRequest(
            String model,
            List<RequestMessage> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature) {}

    private record RequestMessage(String role, String content) {}

    private record CohereResponse(
            ResponseMessage message,
            @JsonProperty("finish_reason") String finishReason,
            Usage usage) {}

    private record ResponseMessage(String role, List<ContentBlock> content) {}

    private record ContentBlock(String type, String text) {}

    private record Usage(Tokens tokens) {}

    private record Tokens(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens) {}
}
