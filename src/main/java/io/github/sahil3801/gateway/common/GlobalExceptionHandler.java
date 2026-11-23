package io.github.sahil3801.gateway.common;

import io.github.sahil3801.gateway.provider.ProviderException;
import java.net.URI;
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
 *  "detail": "The LLM provider failed to answer.", "errorCode": "PROVIDER_ERROR"}
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
     * Provider failure. Step 1 has a single provider, so its failure is the request's failure.
     * Step 3 (fallback) replaces this with an "all providers failed" error.
     */
    @ExceptionHandler(ProviderException.class)
    public ProblemDetail handleProvider(ProviderException e) {
        // Full detail goes to the server log only; the client gets a generic, safe message.
        log.warn("Provider call failed: {}", e.getMessage());
        return switch (e.reason()) {
            case NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                    "No LLM provider is configured.", "PROVIDER_NOT_CONFIGURED");
            case BAD_REQUEST -> problem(HttpStatus.BAD_REQUEST,
                    "The LLM provider rejected the request (e.g. prompt too long for the model).",
                    "PROVIDER_REJECTED_REQUEST");
            default -> problem(HttpStatus.BAD_GATEWAY,
                    "The LLM provider failed to answer (" + e.reason() + ").", "PROVIDER_ERROR");
        };
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

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("about:blank"));
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
