package ai.kukuvaia.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link LlmProvidersProperties} as a Spring-managed
 * {@code @ConfigurationProperties} bean so it participates in Spring Boot's
 * relaxed binding (env vars, system properties, YAML).
 *
 * <p>The bean is the single source of truth for provider / model / role config.
 * {@link ai.kukuvaia.provider.service.ChatModelCache} consumes it at startup
 * instead of querying the database.
 */
@Configuration
public class LlmProvidersConfig {

    @Bean
    @ConfigurationProperties(prefix = "kukuvaia.llm-providers")
    public LlmProvidersProperties llmProvidersProperties() {
        return new LlmProvidersProperties();
    }
}
