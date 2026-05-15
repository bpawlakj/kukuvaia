package ai.kukuvaia.provider.dto;

import java.util.Map;

/**
 * Result of a model test — a minimal chat completion to verify the model responds.
 *
 * <p>{@code emptyContent} + {@code detectedReasoningField} signal that the model
 * returned reasoning but no final content — typical of thinking models called
 * without a {@code reasoning} request parameter. The UI uses this to prompt the
 * operator to enable the thinking flag and retry.
 */
public record ModelTestResult(
        long latencyMs,
        String response,
        boolean emptyContent,
        String detectedReasoningField,
        Map<String, Object> suggestedConfig
) {
    public static ModelTestResult ok(long latencyMs, String response) {
        return new ModelTestResult(latencyMs, response, false, null, Map.of());
    }

    public static ModelTestResult thinkingDetected(long latencyMs, String reasoningPreview, String field) {
        return new ModelTestResult(
                latencyMs,
                reasoningPreview,
                true,
                field,
                Map.of("thinking", true, "reasoning_effort", "medium")
        );
    }
}
