package ai.kukuvaia.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session one-shot escalation flag used by {@code /escalate} slash command.
 * The flag is consumed (test-and-remove) by {@code ModelRoutingAdvisor} at the
 * start of the next turn, so escalation scope is exactly one message.
 */
@Service
public class SessionEscalationService {

    private static final Logger log = LoggerFactory.getLogger(SessionEscalationService.class);

    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public void markEscalate(String sessionId) {
        if (sessionId == null) return;
        pending.add(sessionId);
        log.info("Escalation requested for next turn: session={}", sessionId);
    }

    /** Test-and-remove: returns true once per mark, then stays false until re-marked. */
    public boolean consumeEscalate(String sessionId) {
        if (sessionId == null) return false;
        boolean present = pending.remove(sessionId);
        if (present) {
            log.info("Escalation flag consumed: session={}", sessionId);
        }
        return present;
    }

    /** Visible for tests. */
    int pendingCount() {
        return pending.size();
    }
}
