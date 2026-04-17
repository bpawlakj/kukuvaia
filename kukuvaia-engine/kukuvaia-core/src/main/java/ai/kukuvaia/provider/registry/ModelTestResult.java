package ai.kukuvaia.provider.registry;

/**
 * Result of a model test — a minimal chat completion to verify the model responds.
 */
public record ModelTestResult(
        long latencyMs,
        String response
) {}
