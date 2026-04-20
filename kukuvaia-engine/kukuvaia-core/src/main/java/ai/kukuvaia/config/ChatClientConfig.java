package ai.kukuvaia.config;

import ai.kukuvaia.advisors.LoopDetectionAdvisor;
import ai.kukuvaia.advisors.ModelRoutingAdvisor;
import ai.kukuvaia.advisors.SessionContextAdvisor;
import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.harness.HarnessAdvisor;
import ai.kukuvaia.memory.advisor.SmartMemoryAdvisor;
import ai.kukuvaia.security.DataMaskingAdvisor;
import ai.kukuvaia.provider.ProviderAuditLog;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import ai.kukuvaia.tools.DelegationTools;
import ai.kukuvaia.tools.MemoryTools;
import ai.kukuvaia.tools.PlanningTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;

/**
 * ChatClient bean with composable advisor chain and tool registration.
 *
 * Advisor chain execution order (by precedence):
 * 1. ProviderAuditLog        (HIGHEST_PRECEDENCE)      — log provider/model usage
 * 2. IntentDetectionAdvisor   (HIGHEST_PRECEDENCE + 10) — classify user intent
 * 3. ModelRoutingAdvisor      (HIGHEST_PRECEDENCE + 12) — select model tier by complexity
 * 4. LoopDetectionAdvisor     (HIGHEST_PRECEDENCE + 13) — detect tool-call loops, trigger escalation
 * 5. SmartMemoryAdvisor       (HIGHEST_PRECEDENCE + 5)  — inject relevant memories
 * 6. ToolResultSanitizing     (default)                 — sanitize tool outputs
 * 7. MessageChatMemoryAdvisor (default)                 — inject conversation history
 */
@Configuration
public class ChatClientConfig {

    @Bean
    ToolCallbackProvider javaToolCallbackProvider(MemoryTools memoryTools, PlanningTools planningTools,
                                                  DelegationTools delegationTools,
                                                  ai.kukuvaia.tools.PlanRegistryTools planRegistryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(memoryTools, planningTools, delegationTools, planRegistryTools)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          ChatMemory chatMemory,
                          ProviderAuditLog providerAuditLog,
                          ToolResultSanitizingAdvisor toolResultSanitizingAdvisor,
                          SmartMemoryAdvisor smartMemoryAdvisor,
                          ModelRoutingAdvisor modelRoutingAdvisor,
                          LoopDetectionAdvisor loopDetectionAdvisor,
                          DataMaskingAdvisor dataMaskingAdvisor,
                          HarnessAdvisor harnessAdvisor,
                          PlanningModeService planningModeService,
                          SessionContextAdvisor sessionContextAdvisor,
                          ToolCallingManager toolCallingManager,
                          Collection<ToolCallbackProvider> toolCallbackProviders) {

        var clientBuilder = builder
                .defaultAdvisors(
                        providerAuditLog,
                        modelRoutingAdvisor,
                        loopDetectionAdvisor,
                        dataMaskingAdvisor,
                        harnessAdvisor,
                        planningModeService,
                        sessionContextAdvisor,
                        toolResultSanitizingAdvisor,
                        smartMemoryAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        ToolCallAdvisor.builder()
                                .toolCallingManager(toolCallingManager)
                                .build()
                );

        // Register all @Tool methods as default tools
        for (ToolCallbackProvider provider : toolCallbackProviders) {
            clientBuilder.defaultToolCallbacks(provider.getToolCallbacks());
        }

        return clientBuilder.build();
    }
}
