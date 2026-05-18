package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.kukuvaia.memory.repository.ConversationSummaryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

@DisplayName("ConversationSummaryAdvisor — resume-side summary injection")
@ExtendWith(MockitoExtension.class)
class ConversationSummaryAdvisorTest {

    @Mock private ConversationSummaryRepository repo;
    @Mock private AdvisorChain chain;

    private ConversationSummaryAdvisor advisor(boolean enabled) {
        return new ConversationSummaryAdvisor(repo, enabled);
    }

    private static ChatClientRequest request(List<Message> messages, String sessionId) {
        var builder = ChatClientRequest.builder().prompt(new Prompt(messages));
        if (sessionId != null) builder.context("chat_memory_conversation_id", sessionId);
        return builder.build();
    }

    @Test
    @DisplayName("disabled — no DB read, request passes through unchanged")
    void disabled_passesThrough() {
        var req = request(List.of(new SystemMessage("persona"), new UserMessage("hi")), "s1");

        var out = advisor(false).before(req, chain);

        assertThat(out.prompt().getInstructions()).hasSize(2);
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("no session id in context — pass-through, no DB lookup")
    void noSessionId_passesThrough() {
        var req = request(List.of(new SystemMessage("persona"), new UserMessage("hi")), null);

        var out = advisor(true).before(req, chain);

        assertThat(out.prompt().getInstructions()).hasSize(2);
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("no persisted summary for session — pass-through")
    void emptySummary_passesThrough() {
        when(repo.findBySessionId("s1")).thenReturn(Optional.empty());
        var req = request(List.of(new SystemMessage("persona"), new UserMessage("hi")), "s1");

        var out = advisor(true).before(req, chain);

        assertThat(out.prompt().getInstructions()).hasSize(2);
    }

    @Test
    @DisplayName("persisted summary present — injected as SystemMessage right after persona")
    void summaryPresent_injectedAfterPersona() {
        when(repo.findBySessionId("s1")).thenReturn(Optional.of("user is editing rule X"));
        var req = request(List.of(
                new SystemMessage("persona prompt"),
                new UserMessage("what's the status?")),
                "s1");

        var out = advisor(true).before(req, chain);

        List<Message> after = out.prompt().getInstructions();
        assertThat(after).hasSize(3);
        assertThat(after.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(after.get(0).getText()).isEqualTo("persona prompt");
        assertThat(after.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(after.get(1).getText()).startsWith(ConversationSummaryAdvisor.SUMMARY_PREFIX);
        assertThat(after.get(1).getText()).contains("user is editing rule X");
        assertThat(after.get(2)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("summary already injected (double-fire safety) — no re-injection")
    void alreadyInjected_skipsReInjection() {
        // No repo stub: the advisor must short-circuit on containsSummary() check
        // before any DB lookup. The unused-stub failure would catch a regression here.
        var req = request(List.of(
                new SystemMessage("persona"),
                new SystemMessage(ConversationSummaryAdvisor.SUMMARY_PREFIX + "\nprior summary"),
                new UserMessage("hi")),
                "s1");

        var out = advisor(true).before(req, chain);

        assertThat(out.prompt().getInstructions()).hasSize(3);
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("no SystemMessage at all — summary prepended at position 0")
    void noSystemMessage_prependedAtHead() {
        when(repo.findBySessionId("s1")).thenReturn(Optional.of("hello"));
        var req = request(List.of(new UserMessage("hi")), "s1");

        var out = advisor(true).before(req, chain);

        List<Message> after = out.prompt().getInstructions();
        assertThat(after).hasSize(2);
        assertThat(after.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(after.get(0).getText()).startsWith(ConversationSummaryAdvisor.SUMMARY_PREFIX);
    }
}
