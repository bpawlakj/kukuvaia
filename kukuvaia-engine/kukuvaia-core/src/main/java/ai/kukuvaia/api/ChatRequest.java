package ai.kukuvaia.api;

/**
 * Request body for POST /api/chat.
 */
public record ChatRequest(String sessionId, String message) {
}
