package ai.kukuvaia.api;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.memory.model.KukuvaiaSession;
import ai.kukuvaia.memory.repository.SessionRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Session CRUD endpoints.
 * Combines Spring AI chat memory (message history) with kukuvaia sessions (metadata, names).
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatMemoryRepository chatMemoryRepository;
    private final SessionRepository sessionRepository;
    private final PlanningModeService planningModeService;
    private final PersonaService personaService;

    public SessionController(ChatMemoryRepository chatMemoryRepository,
                             SessionRepository sessionRepository,
                             PlanningModeService planningModeService,
                             PersonaService personaService) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.sessionRepository = sessionRepository;
        this.planningModeService = planningModeService;
        this.personaService = personaService;
    }

    /**
     * List all sessions with metadata.
     * Merges Spring AI conversation IDs with kukuvaia session names.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        var conversationIds = chatMemoryRepository.findConversationIds();

        var sessions = conversationIds.stream().map(id -> {
            var kukuSession = sessionRepository.findById(id);
            int messageCount = chatMemoryRepository.findByConversationId(id).size();

            return Map.<String, Object>of(
                    "id", id,
                    "name", kukuSession.map(KukuvaiaSession::name).orElse(""),
                    "messageCount", messageCount,
                    "updatedAt", kukuSession.map(s -> s.updatedAt().toString()).orElse("")
            );
        }).toList();

        return ResponseEntity.ok(sessions);
    }

    /**
     * Get session detail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String id) {
        var messages = chatMemoryRepository.findByConversationId(id);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var kukuSession = sessionRepository.findById(id);
        return ResponseEntity.ok(Map.of(
                "sessionId", id,
                "name", kukuSession.map(KukuvaiaSession::name).orElse(""),
                "messageCount", messages.size()
        ));
    }

    /**
     * Rename a session.
     */
    @PutMapping("/{id}/name")
    public ResponseEntity<Map<String, Object>> renameSession(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "");
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }

        // Ensure user + session exist, then rename
        sessionRepository.ensureExists(id, "bartek"); // TODO: resolve from auth context
        sessionRepository.updateName(id, name);

        return ResponseEntity.ok(Map.of("id", id, "name", name));
    }

    /**
     * Inspect planning state for a session. Returns phase and discovery facts (known / excluded / gaps).
     * Observability surface for the DISCOVERY state machine.
     */
    @GetMapping("/{id}/planning")
    public ResponseEntity<Map<String, Object>> getPlanningState(@PathVariable String id) {
        return planningModeService.getSession(id)
                .<ResponseEntity<Map<String, Object>>>map(session -> ResponseEntity.ok(Map.of(
                        "sessionId", id,
                        "task", session.task(),
                        "phase", session.phase().name(),
                        "startedAt", session.startedAt().toString(),
                        "facts", Map.of(
                                "known", session.facts().knownFacts(),
                                "excluded", session.facts().excludedOptions(),
                                "gaps", session.facts().remainingGaps(),
                                "ambiguities", session.facts().ambiguities()
                        )
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Bind a persona to this session for subsequent chat / command requests.
     *
     * <p>Body: {@code {"persona": "rule-editor"}}. Returns 400 with an explanation when the
     * persona name does not exist — caller decides whether to retry or fall back. Used by ad-hoc
     * persona switching from the CLI; the CLI also pushes persona on every chat request, so this
     * endpoint is mainly useful for tooling and diagnostics.
     */
    @PutMapping("/{id}/persona")
    public ResponseEntity<Map<String, Object>> setPersona(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String persona = body != null ? body.getOrDefault("persona", "") : "";
        if (persona.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "persona is required"));
        }
        try {
            personaService.setActivePersona(id, persona);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("sessionId", id, "persona", persona));
    }

    /**
     * Delete session (both chat memory and kukuvaia session).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        chatMemoryRepository.deleteByConversationId(id);
        sessionRepository.archive(id);
        return ResponseEntity.noContent().build();
    }
}
