package ai.kukuvaia.config;

import ai.kukuvaia.advisors.ContextCompactionAdvisor;
import ai.kukuvaia.advisors.LoopDetectionAdvisor;
import ai.kukuvaia.advisors.ModelRoutingAdvisor;
import ai.kukuvaia.advisors.SessionContextAdvisor;
import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.harness.HarnessAdvisor;
import ai.kukuvaia.memory.advisor.SmartMemoryAdvisor;
import ai.kukuvaia.security.DataMaskingAdvisor;
import ai.kukuvaia.provider.service.ProviderAuditLog;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import ai.kukuvaia.tools.DelegationTools;
import ai.kukuvaia.tools.MemoryTools;
import ai.kukuvaia.tools.PlanningTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Collection;

/**
 * ChatClient bean with composable advisor chain and tool registration.
 *
 * Advisor chain execution order (by precedence):
 * 1. ProviderAuditLog          (HIGHEST_PRECEDENCE)        — log provider/model usage
 * 2. IntentDetectionAdvisor    (HIGHEST_PRECEDENCE + 10)   — classify user intent
 * 3. ModelRoutingAdvisor       (HIGHEST_PRECEDENCE + 12)   — select model tier by complexity
 * 4. LoopDetectionAdvisor      (HIGHEST_PRECEDENCE + 13)   — detect tool-call loops, trigger escalation
 * 5. SmartMemoryAdvisor        (HIGHEST_PRECEDENCE + 5)    — inject relevant memories
 * 6. ToolResultSanitizing      (default)                   — sanitize tool outputs
 * 7. MessageChatMemoryAdvisor  (HIGHEST_PRECEDENCE + 1000) — inject conversation history
 * 8. ContextCompactionAdvisor  (HIGHEST_PRECEDENCE + 1100) — token-threshold prompt compaction (P24 Phase A)
 * 9. ToolCallAdvisor           (default)                   — multi-round tool-calling loop
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
    @Primary
    ChatClient chatClient(ChatClient.Builder builder,
                          ChatMemory chatMemory,
                          ProviderAuditLog providerAuditLog,
                          ToolResultSanitizingAdvisor toolResultSanitizingAdvisor,
                          SmartMemoryAdvisor smartMemoryAdvisor,
                          ModelRoutingAdvisor modelRoutingAdvisor,
                          LoopDetectionAdvisor loopDetectionAdvisor,
                          HarnessAdvisor harnessAdvisor,
                          PlanningModeService planningModeService,
                          SessionContextAdvisor sessionContextAdvisor,
                          ContextCompactionAdvisor contextCompactionAdvisor,
                          ToolCallingManager toolCallingManager,
                          Collection<ToolCallbackProvider> toolCallbackProviders) {

        var clientBuilder = builder
                .defaultAdvisors(
                        providerAuditLog,
                        modelRoutingAdvisor,
                        loopDetectionAdvisor,
                        harnessAdvisor,
                        planningModeService,
                        sessionContextAdvisor,
                        toolResultSanitizingAdvisor,
                        smartMemoryAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        contextCompactionAdvisor,
                        ToolCallAdvisor.builder()
                                .toolCallingManager(toolCallingManager)
                                .build()
                );

        // Register all @Tool methods as default tools. Each callback is wrapped with the MCP
        // error translator — the wrapper is a pass-through for tools that cannot fail with the
        // recognised SSE-session pattern (kukuvaia-internal @Tool methods, etc.) and cheap to
        // apply uniformly. For MCP-backed tools the wrapper turns "Session not found" 404 stack
        // traces into a one-paragraph instruction the LLM can forward to the operator, so the
        // model does not halucynować a wrong cause when the validation-engine peer restarts.
        for (ToolCallbackProvider provider : toolCallbackProviders) {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            ToolCallback[] wrapped = new ToolCallback[callbacks.length];
            for (int i = 0; i < callbacks.length; i++) {
                wrapped[i] = new McpErrorTranslatingToolCallback(callbacks[i]);
            }
            clientBuilder.defaultToolCallbacks(wrapped);
        }

        return clientBuilder.build();
    }

    /**
     * Memory-free ChatClient for non-interactive agent runs (P23).
     *
     * <p>Excludes {@code MessageChatMemoryAdvisor} and {@code SmartMemoryAdvisor} so that an
     * agent run never reads from or writes to {@code SPRING_AI_CHAT_MEMORY} / {@code kukuvaia.memories}
     * — that is P23 invariant #1 + #2. Routing, audit logging, loop detection, harness, and the
     * tool-call advisor are kept so runs benefit from the same routing and observability surface
     * as interactive chat.
     *
     * <p>Tool surface is identical to the primary {@link ChatClient} bean: agent-run callers can
     * still invoke any registered {@code @Tool} method. Future work may give callers a way to
     * opt out of specific tool families per run.
     */
    @Bean(name = "agentRunChatClient")
    ChatClient agentRunChatClient(ChatClient.Builder builder,
                                  ProviderAuditLog providerAuditLog,
                                  ToolResultSanitizingAdvisor toolResultSanitizingAdvisor,
                                  ModelRoutingAdvisor modelRoutingAdvisor,
                                  LoopDetectionAdvisor loopDetectionAdvisor,
                                  HarnessAdvisor harnessAdvisor,
                                  ToolCallingManager toolCallingManager,
                                  Collection<ToolCallbackProvider> toolCallbackProviders) {

        var clientBuilder = builder
                .defaultAdvisors(
                        providerAuditLog,
                        modelRoutingAdvisor,
                        loopDetectionAdvisor,
                        harnessAdvisor,
                        toolResultSanitizingAdvisor,
                        ToolCallAdvisor.builder()
                                .toolCallingManager(toolCallingManager)
                                .build()
                );

        for (ToolCallbackProvider provider : toolCallbackProviders) {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            ToolCallback[] wrapped = new ToolCallback[callbacks.length];
            for (int i = 0; i < callbacks.length; i++) {
                wrapped[i] = new McpErrorTranslatingToolCallback(callbacks[i]);
            }
            clientBuilder.defaultToolCallbacks(wrapped);
        }

        return clientBuilder.build();
    }
}
