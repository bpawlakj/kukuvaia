package ai.kukuvaia.agent.subagent;

import java.util.List;

/**
 * Specialist sub-agent specification loaded from YAML.
 * Defines: identity, prompt, tools, cost controls, provider preference.
 */
public record SubAgentSpec(
        String name,
        String description,
        String provider,           // null = inherit from execution context
        String tier,               // null = use model field. "worker", "supervisor", "advisor" → model_roles
        String systemPrompt,
        List<String> tools,
        String model,
        int maxTokens,
        int maxToolRounds,
        double temperature,
        long timeoutSeconds        // 0 = use default (300s)
) {

    public SubAgentSpec {
        if (maxTokens <= 0) maxTokens = 4096;
        if (maxToolRounds <= 0) maxToolRounds = 10;
        if (timeoutSeconds < 0) timeoutSeconds = 0;
    }
}
