package io.github.sahil3801.gateway.ratelimit;

import io.github.sahil3801.gateway.common.InvalidClientIdException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before the chat controller: identifies the client and applies its rate limit.
 *
 * <p>It runs BEFORE the cache lookup on purpose: a cache hit is cheap for us, but it is still a
 * request, and a client flooding the gateway with the same prompt should still be limited.
 *
 * <p><b>Who is "the client"?</b> The {@code X-Client-Id} header (e.g. "claims-app"); if it is
 * missing, the caller's IP address. Note: without authentication a caller could send a different
 * id each time. Real per-client API keys would close that gap; this step keeps identity simple.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String CLIENT_ID_HEADER = "X-Client-Id";

    /**
     * Allowed client ids: letters, digits, dot, dash, underscore, max 64 chars. The id becomes part
     * of a Redis key, so arbitrary input (huge strings, spaces, ':' separators) is rejected.
     */
    private static final Pattern VALID_CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = resolveClientId(request);
        RateLimiter.Decision decision = rateLimiter.check(clientId);

        // Sent on every response so well-behaved clients can slow down before hitting the limit.
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            // Handled by GlobalExceptionHandler -> clean 429 JSON with a Retry-After header.
            throw new RateLimitExceededException(decision);
        }
        return true;
    }

    private static String resolveClientId(HttpServletRequest request) {
        String header = request.getHeader(CLIENT_ID_HEADER);
        if (header == null || header.isBlank()) {
            // Behind a proxy/load balancer this is the proxy's IP; configure forwarded headers
            // (server.forward-headers-strategy) when deploying behind one.
            return "ip:" + request.getRemoteAddr();
        }
        String id = header.strip();
        if (!VALID_CLIENT_ID.matcher(id).matches()) {
            throw new InvalidClientIdException(CLIENT_ID_HEADER
                    + " must be 1-64 characters: letters, digits, '.', '-' or '_'");
        }
        return "id:" + id;
    }
}
