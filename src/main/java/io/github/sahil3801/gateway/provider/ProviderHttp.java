package io.github.sahil3801.gateway.provider;

import io.github.sahil3801.gateway.config.ProviderSettings;
import io.github.sahil3801.gateway.provider.ProviderException.Reason;
import java.net.http.HttpClient;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared HTTP plumbing for provider adapters, so every provider gets the same timeouts and the same
 * error translation. Without this, each adapter would copy-paste (and slightly mis-copy) both.
 */
public final class ProviderHttp {

    private ProviderHttp() {}

    /**
     * Builds a RestClient for one provider with explicit connect and read timeouts. A provider that
     * hangs must never hang the gateway's request with it.
     *
     * @param builder Spring Boot's pre-configured builder (already knows how to read/write JSON)
     */
    public static RestClient restClient(RestClient.Builder builder, ProviderSettings settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(settings.readTimeout());

        return builder
                .baseUrl(settings.baseUrl())
                // Buffering serialises the JSON body up front so a Content-Length header is sent.
                // Without it the JDK client streams the body "chunked", which some APIs and proxies
                // reject. Our bodies are small, so buffering costs nothing noticeable.
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
                .build();
    }

    /**
     * Converts any exception thrown by RestClient into a {@link ProviderException} with a {@link
     * Reason} the rest of the gateway understands.
     *
     * <p>The provider's error body is intentionally NOT copied into the message: it can be large and
     * may echo request details. The status code and reason are enough to act on.
     */
    public static ProviderException translate(String provider, RestClientException e) {
        if (e instanceof RestClientResponseException http) {
            int status = http.getStatusCode().value();
            Reason reason = switch (status) {
                case 429 -> Reason.RATE_LIMITED;
                case 401, 403 -> Reason.AUTH_FAILED;
                // The model name is part of every request (and of Gemini's URL), so a 404 means our
                // model/endpoint config is wrong, not that the prompt is. Must not stop the fallback.
                case 404 -> Reason.MODEL_NOT_FOUND;
                default -> status >= 500 ? Reason.SERVER_ERROR : Reason.BAD_REQUEST;
            };
            return new ProviderException(provider, reason, status, "HTTP " + status, e);
        }
        if (e instanceof ResourceAccessException) {
            // I/O-level failure: DNS, connection refused, TLS, or the read timeout expired.
            return new ProviderException(
                    provider, Reason.TIMEOUT, null, "no response (timeout or connection failure)", e);
        }
        // e.g. the 2xx body could not be parsed as the JSON we expected.
        return new ProviderException(
                provider, Reason.INVALID_RESPONSE, null, "unreadable response", e);
    }
}
