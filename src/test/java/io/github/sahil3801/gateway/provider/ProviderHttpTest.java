package io.github.sahil3801.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sahil3801.gateway.provider.ProviderException.Reason;
import java.net.SocketTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * The status-code -> Reason mapping is what the fallback chain's decisions rest on, so every
 * branch is pinned down here.
 */
class ProviderHttpTest {

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource({
        "429, RATE_LIMITED",
        "401, AUTH_FAILED",
        "403, AUTH_FAILED",
        "404, MODEL_NOT_FOUND",  // bad/retired model name: config error, must NOT stop the chain
        "400, BAD_REQUEST",
        "422, BAD_REQUEST",
    })
    void mapsClientErrorStatus(int status, Reason expected) {
        ProviderException e = ProviderHttp.translate("groq",
                HttpClientErrorException.create(HttpStatusCode.valueOf(status), "x", null, null, null));

        assertThat(e.reason()).isEqualTo(expected);
        assertThat(e.httpStatus()).isEqualTo(status);
        assertThat(e.provider()).isEqualTo("groq");
    }

    @ParameterizedTest(name = "HTTP {0} -> SERVER_ERROR")
    @CsvSource({"500", "502", "503"})
    void mapsServerErrors(int status) {
        ProviderException e = ProviderHttp.translate("gemini",
                HttpServerErrorException.create(HttpStatusCode.valueOf(status), "x", null, null, null));

        assertThat(e.reason()).isEqualTo(Reason.SERVER_ERROR);
    }

    @Test
    void noResponseAtAllIsTimeout() {
        ProviderException e = ProviderHttp.translate("cohere",
                new ResourceAccessException("I/O error", new SocketTimeoutException("read timed out")));

        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
        assertThat(e.httpStatus()).isNull();
    }

    @Test
    void unreadable2xxBodyIsInvalidResponse() {
        ProviderException e = ProviderHttp.translate("groq", new RestClientException("could not extract body"));

        assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE);
    }
}
