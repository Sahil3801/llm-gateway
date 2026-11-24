package io.github.sahil3801.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sahil3801.gateway.support.DeadRedis;
import io.github.sahil3801.gateway.support.RedisContainer;
import io.github.sahil3801.gateway.support.TestProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sliding-window Lua script running in a real Redis (Testcontainers). */
class RateLimiterIntegrationTest {

    @BeforeEach
    void cleanRedis() {
        RedisContainer.flushAll();
    }

    @Test
    void allowsUpToTheLimitThenRejectsWithRetryAfter() {
        RateLimiter limiter = limiter(3, Duration.ofMinutes(1));

        assertThat(limiter.check("a").remaining()).isEqualTo(2);
        assertThat(limiter.check("a").remaining()).isEqualTo(1);
        assertThat(limiter.check("a").remaining()).isEqualTo(0);

        RateLimiter.Decision rejected = limiter.check("a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMs()).isPositive().isLessThanOrEqualTo(60_000);
    }

    @Test
    void eachClientHasItsOwnBudget() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));

        assertThat(limiter.check("claims-app").allowed()).isTrue();
        assertThat(limiter.check("claims-app").allowed()).isFalse();
        assertThat(limiter.check("chatbot-app").allowed()).isTrue();
    }

    @Test
    void windowSlidesRequestsExpireIndividually() throws InterruptedException {
        RateLimiter limiter = limiter(2, Duration.ofMillis(1_000));

        assertThat(limiter.check("s").allowed()).isTrue();   // t = 0
        Thread.sleep(600);
        assertThat(limiter.check("s").allowed()).isTrue();   // t = 600 ms
        assertThat(limiter.check("s").allowed()).isFalse();  // full

        Thread.sleep(500);                                   // t = 1100 ms: only the FIRST request expired
        assertThat(limiter.check("s").allowed()).isTrue();
        assertThat(limiter.check("s").allowed()).as("second request still inside the window").isFalse();
    }

    @Test
    void rejectedRequestsDoNotExtendTheBlock() throws InterruptedException {
        RateLimiter limiter = limiter(1, Duration.ofMillis(800));
        assertThat(limiter.check("h").allowed()).isTrue();

        for (int i = 0; i < 5; i++) { // keeps hammering while blocked
            assertThat(limiter.check("h").allowed()).isFalse();
            Thread.sleep(100);
        }
        Thread.sleep(400); // ~900 ms after the only ACCEPTED request

        assertThat(limiter.check("h").allowed()).isTrue();
    }

    /** The reason the check is a Lua script: parallel requests must not slip past the limit. */
    @Test
    void parallelRequestsNeverExceedTheLimit() throws Exception {
        RateLimiter limiter = limiter(20, Duration.ofMinutes(1));
        int threads = 50;
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    startTogether.await();
                    return limiter.check("burst").allowed();
                }));
            }
            startTogether.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(20);
        }
    }

    @Test
    void redisDownFailOpenAllowsRequests() {
        RateLimiter limiter = new RateLimiter(DeadRedis.template(),
                TestProperties.withRateLimit(1, Duration.ofMinutes(1), true));

        assertThat(limiter.check("x").allowed()).isTrue();
        assertThat(limiter.check("x").allowed()).isTrue();
    }

    @Test
    void redisDownFailClosedRejectsWithUnavailable() {
        RateLimiter limiter = new RateLimiter(DeadRedis.template(),
                TestProperties.withRateLimit(1, Duration.ofMinutes(1), false));

        assertThatThrownBy(() -> limiter.check("x")).isInstanceOf(RateLimiterUnavailableException.class);
    }

    private static RateLimiter limiter(int requests, Duration window) {
        return new RateLimiter(RedisContainer.template(), TestProperties.withRateLimit(requests, window, true));
    }
}
