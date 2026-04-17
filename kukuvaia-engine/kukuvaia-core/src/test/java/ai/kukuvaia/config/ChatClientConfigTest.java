package ai.kukuvaia.config;

import ai.kukuvaia.advisors.LoopDetectionAdvisor;
import ai.kukuvaia.advisors.ModelRoutingAdvisor;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

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
        var auditLog = new ProviderAuditLog();
        var sanitizer = new ToolResultSanitizingAdvisor();
        var memoryAdvisor = new SmartMemoryAdvisor(mock(SmartMemoryRepository.class), mock(EmbeddingService.class));
        var routingAdvisor = new ModelRoutingAdvisor(mock(ChatModelCache.class), mock(ModelRepository.class));
        var loopAdvisor = new LoopDetectionAdvisor();
        var maskingAdvisor = new DataMaskingAdvisor(
                new MaskingService(true, true, true, true, true, true), true);
        var harnessAdvisor = new HarnessAdvisor(mock(HarnessService.class), true);

        ChatClient client = config.chatClient(builder, memory, auditLog, sanitizer,
                memoryAdvisor, routingAdvisor, loopAdvisor, maskingAdvisor, harnessAdvisor, List.of());

        assertThat(client).isNotNull();
    }
}
