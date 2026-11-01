package ai.kukuvaia.agent.subagent;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Outcome of one sub-agent call: the synthesized text plus the token usage reported by the
 * model (cumulative across tool rounds — Spring AI's {@code ToolCallingAdvisor} accumulates it).
 *
 * @param content          the sub-agent's final answer (may be {@code null} if the model returned nothing)
 * @param promptTokens     prompt tokens reported by the provider (0 when not reported)
 * @param completionTokens completion tokens reported by the provider (0 when not reported)
 * @param usageReported    {@code false} when the provider returned no usage at all — callers that
 *                         enforce budgets must then fall back to a conservative estimate (fail closed)
 */
public record SubAgentResult(String content, int promptTokens, int completionTokens, boolean usageReported) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    static SubAgentResult from(ChatResponse response) {
        if (response == null) {
            return new SubAgentResult(null, 0, 0, false);
        }
        String content = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage == null || usage instanceof EmptyUsage) {
            return new SubAgentResult(content, 0, 0, false);
        }
        int prompt = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completion = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new SubAgentResult(content, prompt, completion, prompt + completion > 0);
    }
}
