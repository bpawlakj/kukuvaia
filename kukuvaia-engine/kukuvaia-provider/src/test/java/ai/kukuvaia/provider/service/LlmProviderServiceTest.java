package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.provider.service.ChatModelCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("LlmProviderService — model resolution facade")
@ExtendWith(MockitoExtension.class)
class LlmProviderServiceTest {

    @Mock private ChatModelCache chatModelCache;
    @Mock private ChatModel mockChatModel;
    @Mock private ChatModel legacyModel;

    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(chatModelCache);
    }

    @Test
    @DisplayName("resolve with role name — returns from cache")
    void resolve_roleName_returnsFromCache() {
        when(chatModelCache.getByRole("advisor")).thenReturn(mockChatModel);

        ChatModel result = service.resolve("advisor", ExecutionContext.INTERACTIVE);

        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("resolve with null — falls back to supervisor role")
    void resolve_null_fallsToSupervisor() {
        when(chatModelCache.getByRole("supervisor")).thenReturn(mockChatModel);

        ChatModel result = service.resolve(null, ExecutionContext.INTERACTIVE);

        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("resolve with empty string — falls back to supervisor role")
    void resolve_empty_fallsToSupervisor() {
        when(chatModelCache.getByRole("supervisor")).thenReturn(mockChatModel);

        ChatModel result = service.resolve("", ExecutionContext.DAEMON);

        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("resolve unknown name — tries legacy, then supervisor")
    void resolve_unknownName_triesLegacyThenSupervisor() {
        when(chatModelCache.getByRole("unknown")).thenReturn(null);
        when(chatModelCache.getByRole("supervisor")).thenReturn(mockChatModel);

        ChatModel result = service.resolve("unknown", ExecutionContext.INTERACTIVE);

        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("resolve with legacy provider — returns legacy when cache misses")
    void resolve_legacyProvider_returnsLegacy() {
        when(chatModelCache.getByRole("copilot")).thenReturn(null);
        service.register("copilot", legacyModel);

        ChatModel result = service.resolve("copilot", ExecutionContext.INTERACTIVE);

        assertThat(result).isSameAs(legacyModel);
    }

    @Test
    @DisplayName("resolve — nothing available — throws ProviderNotAvailableException")
    void resolve_nothingAvailable_throws() {
        when(chatModelCache.getByRole(any())).thenReturn(null);

        assertThatThrownBy(() -> service.resolve("missing", ExecutionContext.INTERACTIVE))
                .isInstanceOf(LlmProviderService.ProviderNotAvailableException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("resolveByRole — role exists — returns model")
    void resolveByRole_exists_returnsModel() {
        when(chatModelCache.getByRole("worker")).thenReturn(mockChatModel);

        ChatModel result = service.resolveByRole("worker");

        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("resolveByRole — role missing — throws")
    void resolveByRole_missing_throws() {
        when(chatModelCache.getByRole("fantasy")).thenReturn(null);

        assertThatThrownBy(() -> service.resolveByRole("fantasy"))
                .isInstanceOf(LlmProviderService.ProviderNotAvailableException.class);
    }

    @Test
    @DisplayName("isAvailable — checks both cache and legacy")
    void isAvailable_checksBoth() {
        when(chatModelCache.getByRole("advisor")).thenReturn(mockChatModel);
        when(chatModelCache.getByRole("copilot")).thenReturn(null);
        service.register("copilot", legacyModel);

        assertThat(service.isAvailable("advisor")).isTrue();
        assertThat(service.isAvailable("copilot")).isTrue();
        assertThat(service.isAvailable("nonexistent")).isFalse();
    }
}
