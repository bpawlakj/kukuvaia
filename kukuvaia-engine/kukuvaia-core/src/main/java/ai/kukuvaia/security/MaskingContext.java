package ai.kukuvaia.security;

import java.util.Map;

/**
 * Request-scoped mapping between masked tokens and original values.
 * Used by {@link DataMaskingAdvisor} to unmask LLM responses.
 */
public record MaskingContext(
        String maskedText,
        Map<String, String> tokenToOriginal
) {

    public boolean isEmpty() {
        return tokenToOriginal == null || tokenToOriginal.isEmpty();
    }
}
