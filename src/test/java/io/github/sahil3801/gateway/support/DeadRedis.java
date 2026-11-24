package io.github.sahil3801.gateway.support;

import java.net.ServerSocket;
import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A Redis client pointed at a port where nothing listens, to test "Redis is down" behaviour. */
public final class DeadRedis {

    private DeadRedis() {}

    public static StringRedisTemplate template() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", freePort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        factory.start();
        return new StringRedisTemplate(factory);
    }

    /** A port that was free a moment ago, so (almost certainly) nothing is listening on it. */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
