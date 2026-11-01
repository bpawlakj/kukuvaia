package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.provider.model.ProviderRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LlmProviderService — daemon provider policy (SmartGate default, Copilot opt-in)")
class LlmProviderServiceDaemonPolicyTest {

    private final ChatModelCache cache = mock(ChatModelCache.class);
    private final ChatModel smartGateModel = mock(ChatModel.class);
    private final ChatModel copilotModel = mock(ChatModel.class);
    private LlmProviderService service;

    private static ProviderRecord provider(String name, String type, String baseUrl) {
        return new ProviderRecord(UUID.randomUUID(), name, type, baseUrl, "KEY", true, 0,
                Map.of(), Instant.now(), Instant.now());
    }

    private static final ProviderRecord SMARTGATE =
            provider("smartgate", "openai", "https://smartgate.example.internal/api/ai");
    private static final ProviderRecord COPILOT =
            provider("github", "openai", "https://api.githubcopilot.com");

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(cache, "daemon");
    }

    private void role(String role, ChatModel model, ProviderRecord provider) {
        when(cache.getByRole(role)).thenReturn(model);
        when(cache.getProviderForRole(role)).thenReturn(Optional.of(provider));
    }

    @Test
    @DisplayName("DAEMON without explicit provider resolves the configured daemon role")
    void daemon_usesDaemonRole() {
        role("daemon", smartGateModel, SMARTGATE);
        role("supervisor", copilotModel, COPILOT);

        assertThat(service.resolve(null, ExecutionContext.DAEMON)).isSameAs(smartGateModel);
    }

    @Test
    @DisplayName("DAEMON falls back to supervisor when no daemon role is configured")
    void daemon_fallsBackToSupervisor() {
        role("supervisor", smartGateModel, SMARTGATE);

        assertThat(service.resolve(null, ExecutionContext.DAEMON)).isSameAs(smartGateModel);
    }

    @Test
    @DisplayName("DAEMON refuses an implicit fallback onto Copilot (fail closed)")
    void daemon_refusesImplicitCopilot() {
        role("supervisor", copilotModel, COPILOT);

        assertThatThrownBy(() -> service.resolve(null, ExecutionContext.DAEMON))
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class)
                .hasMessageContaining("Copilot");
    }

    @Test
    @DisplayName("DAEMON refuses a daemon role that is itself mapped to Copilot")
    void daemon_refusesCopilotDaemonRole() {
        role("daemon", copilotModel, provider("copilot", "copilot", "https://copilot-proxy.example"));

        assertThatThrownBy(() -> service.resolve(null, ExecutionContext.DAEMON))
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class);
    }

    @Test
    @DisplayName("DAEMON with an explicit provider: copilot is the deliberate opt-in")
    void daemon_explicitCopilotAllowed() {
        role("copilot", copilotModel, COPILOT);

        assertThat(service.resolve("copilot", ExecutionContext.DAEMON)).isSameAs(copilotModel);
    }

    @Test
    @DisplayName("DAEMON tier (specialist config) resolving to Copilot is refused")
    void daemon_tierCopilotRefused() {
        role("worker", copilotModel, COPILOT);

        assertThatThrownBy(() -> service.resolveByRole("worker", ExecutionContext.DAEMON))
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class);
        assertThat(service.resolveByRole("worker", ExecutionContext.INTERACTIVE)).isSameAs(copilotModel);
    }

    @Test
    @DisplayName("INTERACTIVE behaviour is unchanged: supervisor fallback, Copilot allowed")
    void interactive_unchanged() {
        role("supervisor", copilotModel, COPILOT);

        assertThat(service.resolve(null, ExecutionContext.INTERACTIVE)).isSameAs(copilotModel);
    }

    @Test
    @DisplayName("Copilot detection: name/type 'copilot' or a githubcopilot.com host")
    void copilotDetection() {
        assertThat(LlmProviderService.isCopilot(COPILOT)).isTrue();
        assertThat(LlmProviderService.isCopilot(provider("copilot", "openai", "https://x"))).isTrue();
        assertThat(LlmProviderService.isCopilot(provider("x", "copilot", "https://x"))).isTrue();
        assertThat(LlmProviderService.isCopilot(provider("x", "openai", "https://proxy.githubcopilot.com/v1"))).isTrue();
        assertThat(LlmProviderService.isCopilot(SMARTGATE)).isFalse();
        assertThat(LlmProviderService.isCopilot(provider("x", "openai", "https://notgithubcopilot.com"))).isFalse();
    }

    @Test
    @DisplayName("review #1: DAEMON parallel workers exclude Copilot-backed and metadata-less worker roles")
    void daemon_workerModels_excludeCopilot() {
        ChatModel worker2 = mock(ChatModel.class);
        when(cache.getWorkerRoles()).thenReturn(java.util.List.of("worker", "worker-2", "worker-3"));
        role("worker", copilotModel, COPILOT);
        role("worker-2", worker2, SMARTGATE);
        when(cache.getByRole("worker-3")).thenReturn(mock(ChatModel.class));
        when(cache.getProviderForRole("worker-3")).thenReturn(Optional.empty());

        assertThat(service.resolveWorkerModels(ExecutionContext.DAEMON)).containsExactly(worker2);
    }

    @Test
    @DisplayName("review #2: DAEMON refuses an assigned role without provider metadata (fail closed)")
    void daemon_missingProviderMetadata_refused() {
        when(cache.getByRole("daemon")).thenReturn(smartGateModel);
        when(cache.getProviderForRole("daemon")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(null, ExecutionContext.DAEMON))
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class)
                .hasMessageContaining("provider metadata");
    }

    @Test
    @DisplayName("review #7: explicit 'supervisor' that maps to Copilot is not the Copilot opt-in")
    void daemon_explicitNonCopilotNameMappedToCopilot_refused() {
        role("supervisor", copilotModel, COPILOT);

        assertThatThrownBy(() -> service.resolve("supervisor", ExecutionContext.DAEMON))
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class);
        assertThat(service.resolve("supervisor", ExecutionContext.INTERACTIVE)).isSameAs(copilotModel);
    }

    @Test
    @DisplayName("D2 review #4: under DAEMON legacy providers (no metadata) are refused unless the name is the Copilot opt-in")
    void daemon_legacyProviders() {
        service.register("default", copilotModel);
        service.register("copilot", copilotModel);

        assertThatThrownBy(() -> service.resolve("default", ExecutionContext.DAEMON))
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class);
        assertThat(service.resolve("copilot", ExecutionContext.DAEMON)).isSameAs(copilotModel);
        assertThatThrownBy(() -> service.resolve(null, ExecutionContext.DAEMON))
                .as("legacy default is never the implicit daemon fallback")
                .isInstanceOf(LlmProviderService.DaemonProviderPolicyException.class);
        assertThat(service.resolve("default", ExecutionContext.INTERACTIVE)).isSameAs(copilotModel);
    }
}
