package ai.kukuvaia.provider.transport;

/**
 * Wire-format dialects we support behind a single {@link ChatTransport} interface.
 *
 * <p>The OpenAI surface is increasingly multi-headed and Spring AI only speaks the
 * legacy {@code /chat/completions} format. GitHub Copilot routes different models to
 * different endpoints (GPT-5.x → Responses, Claude → Anthropic Messages, older OpenAI
 * → Chat Completions). Each enum value pins one wire dialect.
 *
 * <p>New providers/dialects (Bedrock, Vertex, ...) extend this enum and ship a matching
 * {@link ChatTransport} implementation; the rest of the registry stays unchanged.
 */
public enum TransportType {
    /** OpenAI-compatible {@code POST /chat/completions}. Default Spring AI path. */
    OPENAI_CHAT_COMPLETIONS,

    /** OpenAI {@code POST /responses}. Required for GPT-5.x, o-series, Gemini-via-Copilot. */
    OPENAI_RESPONSES,

    /** Anthropic Messages {@code POST /v1/messages}. Reserved — Phase 3. */
    ANTHROPIC_MESSAGES;

    /** Parse from config string, case-insensitive. Returns null on unknown values. */
    public static TransportType fromString(String value) {
        if (value == null) return null;
        String normalised = value.trim().toLowerCase().replace('_', '-');
        return switch (normalised) {
            case "openai-chat-completions", "chat-completions", "openai" -> OPENAI_CHAT_COMPLETIONS;
            case "openai-responses", "responses" -> OPENAI_RESPONSES;
            case "anthropic-messages", "messages", "anthropic" -> ANTHROPIC_MESSAGES;
            default -> null;
        };
    }
}
