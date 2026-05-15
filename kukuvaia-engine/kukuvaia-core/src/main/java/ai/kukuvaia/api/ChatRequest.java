package ai.kukuvaia.api;

/**
 * Request body for POST /api/chat.
 *
 * <p>{@code persona} is optional — when present it (re)binds the session to that persona before
 * the message is routed. CLI passes the env-var {@code KUKUVAIA_PERSONA} on every chat request so
 * persona stickiness survives engine restarts (the per-session map in {@code PersonaService} is
 * in-memory only). Unknown personas are surfaced as a 400 by {@code ChatController}.
 */
public record ChatRequest(String sessionId, String message, String persona) {
    public ChatRequest(String sessionId, String message) {
        this(sessionId, message, null);
    }
}
