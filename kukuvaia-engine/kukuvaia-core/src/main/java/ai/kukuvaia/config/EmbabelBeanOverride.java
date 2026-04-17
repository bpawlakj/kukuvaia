package ai.kukuvaia.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Removes Embabel's default {@code modelProvider} bean definition before it gets instantiated.
 *
 * Embabel's {@code AgentPlatformConfiguration.modelProvider()} crashes when no LLM providers
 * are configured (expects at least one model, defaults to gpt-4.1-mini). kukuvaia provides
 * its own {@code ModelProvider} via {@code EmbabelModelBridgeConfig.kukuvaiaModelProvider()}
 * backed by the DB-driven provider registry.
 *
 * This post-processor runs before any beans are created, preventing the crash.
 */
@Component
public class EmbabelBeanOverride implements BeanDefinitionRegistryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmbabelBeanOverride.class);

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // Embabel's modelProvider crashes without pre-configured LLMs.
        // kukuvaia provides its own via EmbabelModelBridgeConfig (DB-backed, lazy).
        removeBeanIfPresent(registry, "modelProvider");

        // Embabel's MCP client conflicts with Spring AI's (duplicate mcpSyncClients bean).
        // kukuvaia uses Spring AI's MCP client directly.
        removeBeanIfPresent(registry, "mcpSyncClients");
    }

    private void removeBeanIfPresent(BeanDefinitionRegistry registry, String beanName) {
        if (registry.containsBeanDefinition(beanName)) {
            registry.removeBeanDefinition(beanName);
            log.info("Removed Embabel bean '{}' — kukuvaia provides its own", beanName);
        }
    }
}
