package ai.kukuvaia.provider.copilot;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import ai.kukuvaia.provider.service.ChatModelCache;

/**
 * Published by {@link CopilotTokenProvider} whenever the cached Copilot bearer is
 * refreshed (or wiped on logout — {@code expiresAt == EPOCH}). Listeners — most
 * importantly {@code ChatModelCache} — invalidate any {@code OpenAiApi} instance that
 * captured the previous bearer string so the next chat request rebuilds with the
 * fresh one.
 */
public class CopilotTokenRefreshedEvent extends ApplicationEvent {

    private final Instant expiresAt;

    public CopilotTokenRefreshedEvent(Object source, Instant expiresAt) {
        super(source);
        this.expiresAt = expiresAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
