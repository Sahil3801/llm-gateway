package io.github.sahil3801.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /v1/chat}.
 *
 * <pre>{"prompt": "Summarise this claim: ...", "max_tokens": 256, "temperature": 0.2}</pre>
 *
 * <p>Validation runs before any provider is called, so malformed requests cost nothing.
 *
 * @param prompt required text to send to the model
 * @param maxTokens optional answer length limit (default from config)
 * @param temperature optional randomness 0.0–2.0 (default: provider's own)
 */
public record ChatRequest(
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 32_000, message = "prompt must be at most 32000 characters")
        String prompt,

        @JsonProperty("max_tokens")
        @Min(value = 1, message = "max_tokens must be at least 1")
        @Max(value = 8192, message = "max_tokens must be at most 8192")
        Integer maxTokens,

        @DecimalMin(value = "0.0", message = "temperature must be between 0 and 2")
        @DecimalMax(value = "2.0", message = "temperature must be between 0 and 2")
        Double temperature) {}
