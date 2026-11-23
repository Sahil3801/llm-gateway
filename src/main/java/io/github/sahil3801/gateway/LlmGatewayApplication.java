package io.github.sahil3801.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the LLM gateway.
 *
 * <p>{@code @ConfigurationPropertiesScan} lets us bind typed config records (e.g. provider settings)
 * from application.yml without registering each one by hand.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}
