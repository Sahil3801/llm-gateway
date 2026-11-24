package io.github.sahil3801.gateway.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Redis (in Docker, via Testcontainers) shared by all tests in the run.
 *
 * <p>Started once, lazily, the first time a test asks for it; Testcontainers removes it when the
 * JVM exits. Sharing one container keeps the test run fast; tests isolate themselves with unique
 * keys or {@link #flushAll()}.
 *
 * <p>Requires Docker to be running (Docker Desktop on Windows/macOS).
 */
public final class RedisContainer {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    private static StringRedisTemplate template;

    private RedisContainer() {}

    public static synchronized String host() {
        start();
        return REDIS.getHost();
    }

    public static synchronized int port() {
        start();
        return REDIS.getMappedPort(6379);
    }

    /** A shared template connected to the container (for tests that don't start Spring). */
    public static synchronized StringRedisTemplate template() {
        if (template == null) {
            LettuceConnectionFactory factory =
                    new LettuceConnectionFactory(new RedisStandaloneConfiguration(host(), port()));
            factory.afterPropertiesSet();
            factory.start();
            template = new StringRedisTemplate(factory);
        }
        return template;
    }

    /** Empties Redis so a test starts from a clean state. */
    public static void flushAll() {
        template().execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private static void start() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }
}
