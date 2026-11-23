package io.github.sahil3801.gateway.common;

import io.github.sahil3801.gateway.provider.ProviderException;
import io.github.sahil3801.gateway.service.AllProvidersFailedException;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every exception into a clean JSON error (RFC 7807 "problem detail"). Clients never see a
 * stack trace or an internal class name.
 *
 * <pre>
 * {"type": "about:blank", "title": "Bad Gateway", "status": 502,
 *  "detail": "All LLM providers failed to answer. Please retry later.",
 *  "errorCode": "ALL_PROVIDERS_FAILED",
 *  "attempts": [{"provider": "groq", "outcome": "RATE_LIMITED"}, ...]}
 * </pre>
 *
 * <p>{@code errorCode} is a stable machine-readable value clients can switch on.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 400: request body failed @Valid checks; lists every broken field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, detail, "VALIDATION_ERROR");
    }

    /** 400: body missing or not valid JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON.", "VALIDATION_ERROR");
    }

    /**
     * Every provider in the chain failed or was skipped.
     *
     * <ul>
     *   <li>503 NO_PROVIDER_CONFIGURED: no API key set for any provider (a setup problem).
     *   <li>502 ALL_PROVIDERS_FAILED: providers were called and all failed (an upstream outage).
     * </ul>
     *
     * <p>The response lists each provider and why it failed, e.g. {@code [{"provider":"groq",
     * "outcome":"RATE_LIMITED"}, ...]}. This is safe to expose (no keys, no provider error bodies)
     * and saves the client a support ticket.
     */
    @ExceptionHandler(AllProvidersFailedException.class)
    public ProblemDetail handleAllFailed(AllProvidersFailedException e) {
        log.warn(e.getMessage());
        ProblemDetail pd = e.noneConfigured()
                ? problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "No LLM provider has an API key configured.", "NO_PROVIDER_CONFIGURED")
                : problem(HttpStatus.BAD_GATEWAY,
                        "All LLM providers failed to answer. Please retry later.", "ALL_PROVIDERS_FAILED");
        List<AttemptView> attempts = e.attempts().stream()
                .map(a -> new AttemptView(a.provider(), a.outcome()))
                .toList();
        pd.setProperty("attempts", attempts);
        return pd;
    }

    /**
     * A provider rejected the request itself (e.g. safety filter, prompt too long). The fallback
     * chain stops on this, so it reaches the client as a 400.
     */
    @ExceptionHandler(ProviderException.class)
    public ProblemDetail handleProvider(ProviderException e) {
        // Full detail goes to the server log only; the client gets a generic, safe message.
        log.warn("Provider call failed: {}", e.getMessage());
        if (e.reason() == ProviderException.Reason.BAD_REQUEST) {
            return problem(HttpStatus.BAD_REQUEST,
                    "The LLM provider rejected the request (e.g. blocked by a safety filter or too long).",
                    "PROVIDER_REJECTED_REQUEST");
        }
        // Other reasons are handled by the fallback chain and shouldn't reach here; safe default.
        return problem(HttpStatus.BAD_GATEWAY, "The LLM provider failed to answer.", "PROVIDER_ERROR");
    }

    /**
     * Everything else. Spring's own HTTP errors (404 unknown path, 405 wrong method, 415 wrong
     * content type) keep their status; anything truly unexpected becomes a 500, logged with its
     * stack trace on the server and hidden from the client.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return problem(status, status.getReasonPhrase(), "HTTP_" + status.value());
        }
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.", "INTERNAL_ERROR");
    }

    /** Public view of one attempt: no latency or internal details, fields in a fixed order. */
    private record AttemptView(String provider, String outcome) {}

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("about:blank"));
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
