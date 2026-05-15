package ai.kukuvaia.agentrun;

/**
 * Describes the optional LLM call that follows an agent-run lifecycle. If null on AgentRunSpec,
 * no LLM call is made — the run is a pure tool invocation persisted as-is.
 *
 * @param systemPrompt        system prompt for the call; persisted to agent_runs.instructions.
 *                            Mutually exclusive with personaName at the spec level (the spec
 *                            resolves to one of them before reaching the LLM).
 * @param userPromptTemplate  user-facing prompt; if null AgentRunService falls back to the
 *                            stringified input/tool result
 * @param skipIfEmpty         when the upstream produces no payload, mark the run skipped
 *                            instead of calling the LLM with an empty prompt
 * @param storeEmbedding      embed the response and persist to agent_runs.embedding
 * @param maxOutputTokens     cap on completion tokens; null = model default
 * @param promptCaching       enable provider-side prompt caching (Anthropic explicit, OpenAI implicit)
 * @param similarityContext   optional run-to-run recall before the call
 * @param modelPreference     optional model selection / escalation policy
 */
public record LlmFollowupSpec(
        String systemPrompt,
        String userPromptTemplate,
        boolean skipIfEmpty,
        boolean storeEmbedding,
        Integer maxOutputTokens,
        boolean promptCaching,
        SimilarityContextSpec similarityContext,
        ModelPreferenceSpec modelPreference) {

    public LlmFollowupSpec {
        if (similarityContext == null) similarityContext = SimilarityContextSpec.disabled();
    }
}
