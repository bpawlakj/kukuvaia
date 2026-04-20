package ai.kukuvaia.config;

import ai.kukuvaia.advisors.ComplexityDetector;
import ai.kukuvaia.advisors.LoopDetectionAdvisor;
import ai.kukuvaia.advisors.ModelRoutingAdvisor;
import ai.kukuvaia.advisors.SessionContextAdvisor;
import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.agent.SessionEscalationService;
import ai.kukuvaia.provider.registry.ComplexityMappingService;
import ai.kukuvaia.harness.HarnessAdvisor;
import ai.kukuvaia.harness.HarnessService;
import ai.kukuvaia.memory.advisor.SmartMemoryAdvisor;
import ai.kukuvaia.memory.embedding.EmbeddingService;
import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import ai.kukuvaia.provider.ProviderAuditLog;
import ai.kukuvaia.provider.registry.ChatModelCache;
import ai.kukuvaia.provider.registry.ModelRepository;
import ai.kukuvaia.security.DataMaskingAdvisor;
import ai.kukuvaia.security.MaskingService;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ChatClientConfig — ChatClient bean with advisor chain")
class ChatClientConfigTest {

    @Test
    @DisplayName("chatClient — creates ChatClient with all advisors")
    void chatClient_withAdvisors_createsChatClient() {
        var config = new ChatClientConfig();
        var chatModel = mock(ChatModel.class);
        var builder = ChatClient.builder(chatModel);
        var memory = mock(ChatMemory.class);
        var registry = new SimpleMeterRegistry();
        var auditLog = new ProviderAuditLog(registry);
        var sanitizer = new ToolResultSanitizingAdvisor();
        var memoryAdvisor = new SmartMemoryAdvisor(mock(SmartMemoryRepository.class), mock(EmbeddingService.class), registry);
        var routingAdvisor = new ModelRoutingAdvisor(
                mock(ChatModelCache.class), mock(ModelRepository.class),
                new ai.kukuvaia.advisors.TaskClassifier(),
                mock(PlanningModeService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(ComplexityDetector.class),
                mock(ComplexityMappingService.class),
                new SessionEscalationService(),
                registry);
        var loopAdvisor = new LoopDetectionAdvisor();
        var maskingAdvisor = new DataMaskingAdvisor(
                new MaskingService(true, true, true, true, true, true), true);
        var harnessAdvisor = new HarnessAdvisor(mock(HarnessService.class), true);

        var planningModeService = mock(PlanningModeService.class);
        var sessionContextAdvisor = mock(SessionContextAdvisor.class);
        var toolCallingManager = mock(ToolCallingManager.class);
        ChatClient client = config.chatClient(builder, memory, auditLog, sanitizer,
                memoryAdvisor, routingAdvisor, loopAdvisor, maskingAdvisor, harnessAdvisor,
                planningModeService, sessionContextAdvisor, toolCallingManager, List.of());

        assertThat(client).isNotNull();
    }
}
