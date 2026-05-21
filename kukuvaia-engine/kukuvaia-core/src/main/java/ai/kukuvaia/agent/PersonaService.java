package ai.kukuvaia.agent;

import ai.kukuvaia.extensions.ExtensionLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads persona definitions from classpath and the user's {@code .kukuvaia/personas/} directory.
 * Manages active persona per session.
 *
 * <p>The engine itself ships no concrete personas — those are deployment-supplied via
 * {@code .kukuvaia/personas/*.yaml}. The classpath loader remains as a generic mechanism so
 * tests (and any future bundled personas) can drop YAMLs under {@code classpath:personas/}.
 */
@Service
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);
    private static final String DEFAULT_PERSONA = "assistant";

    private final Map<String, PersonaSpec> personas = new ConcurrentHashMap<>();
    private final Map<String, String> activePersonaBySession = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final SupervisorVerbosity supervisorVerbosity;

    @Autowired
    public PersonaService(
            @Value("${kukuvaia.supervisor.verbosity:FULL}") SupervisorVerbosity supervisorVerbosity,
            ExtensionLoader extensionLoader) {
        this.supervisorVerbosity = supervisorVerbosity;
        log.info("Supervisor verbosity = {}", supervisorVerbosity);
        loadBuiltInPersonas();
        if (extensionLoader != null) {
            extensionLoader.getPersonasDir().ifPresent(this::loadFromDirectory);
        }
    }

    public PersonaService(SupervisorVerbosity supervisorVerbosity) {
        this(supervisorVerbosity, null);
    }

    public PersonaService() {
        this(SupervisorVerbosity.FULL, null);
    }

    public PersonaSpec getActivePersona(String sessionId) {
        String personaName = activePersonaBySession.getOrDefault(sessionId, DEFAULT_PERSONA);
        return personas.getOrDefault(personaName, createDefaultPersona());
    }

    public void setActivePersona(String sessionId, String personaName) {
        if (!personas.containsKey(personaName)) {
            throw new IllegalArgumentException("Unknown persona: " + personaName);
        }
        activePersonaBySession.put(sessionId, personaName);
        log.info("Session {} switched to persona '{}'", sessionId, personaName);
    }

    /**
     * Bind the session to the named persona iff a non-blank name is provided. Used by the chat /
     * command HTTP entry points so the CLI can push {@code KUKUVAIA_PERSONA} on every request
     * without forcing every other caller to know whether the session is already bound.
     *
     * <p>No-op for null/blank input. Throws {@link IllegalArgumentException} for unknown personas
     * — controllers translate that into a 400 so the operator sees the typo immediately rather
     * than getting silent fallback to {@code assistant}.
     */
    public void applyIfPresent(String sessionId, String personaName) {
        if (personaName == null || personaName.isBlank()) {
            return;
        }
        setActivePersona(sessionId, personaName);
    }

    public Map<String, PersonaSpec> allPersonas() {
        return Map.copyOf(personas);
    }

    /**
     * Union of every named (non-default) persona's {@code toolFilter}.
     *
     * <p>Used by {@code /help} to draw the line between "general" tools (always reachable, regardless
     * of which persona is active — typically internal kukuvaia tools like planning) and
     * "persona-specific" tools (only reachable when a particular persona is active — typically MCP
     * tools mounted by that persona). The default persona contributes nothing because its
     * {@code toolFilter} is empty (= "no opinion, all tools allowed").
     */
    public Set<String> namedPersonaToolUnion() {
        Set<String> union = new LinkedHashSet<>();
        for (var entry : personas.entrySet()) {
            if (DEFAULT_PERSONA.equals(entry.getKey())) continue;
            List<String> tf = entry.getValue().toolFilter();
            if (tf != null) union.addAll(tf);
        }
        return union;
    }

    public void loadFromDirectory(Path directory) {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .forEach(this::loadPersonaFile);
        } catch (IOException e) {
            log.warn("Failed to load personas from {}: {}", directory, e.getMessage());
        }
    }

    private void loadBuiltInPersonas() {
        personas.put(DEFAULT_PERSONA, createDefaultPersona());
        loadFromClasspath();
        log.info("Loaded {} built-in persona(s): {}", personas.size(), personas.keySet());
    }

    /**
     * Load every {@code classpath:personas/*.yaml} into the registry. Kept as a generic loader
     * for tests and any future bundled personas — the engine itself ships none. Deployment-supplied
     * personas live in {@code .kukuvaia/personas/} and are picked up via {@link ExtensionLoader}.
     */
    private void loadFromClasspath() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:personas/*.yaml");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    PersonaSpec spec = yamlMapper.readValue(is, PersonaSpec.class);
                    personas.put(spec.name(), spec);
                    log.info("Loaded built-in persona '{}' from classpath:personas/{}",
                            spec.name(), resource.getFilename());
                } catch (IOException e) {
                    log.warn("Failed to parse persona resource {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to enumerate classpath:personas/*.yaml: {}", e.getMessage());
        }
    }

    private PersonaSpec createDefaultPersona() {
        String prompt = switch (supervisorVerbosity) {
            case FULL -> FULL_SUPERVISOR_PROMPT;
            case CONCISE -> CONCISE_SUPERVISOR_PROMPT;
        };
        return new PersonaSpec(
                DEFAULT_PERSONA,
                "General-purpose AI assistant",
                prompt,
                List.of()
        );
    }

    private static final String FULL_SUPERVISOR_PROMPT = """
            You are Kukuvaia, an intelligent AI assistant. Help the user with their requests.

            When the user's phrasing has more than one plausible meaning in context, ask a short clarifying question before acting. Prefer one targeted question over guessing, and never invent facts the user did not state.

            Proactivity rules — applied to every response:
            1. Whenever the Session Context shows unfinished plans (draft/active, session-scoped OR cross-session), surface them immediately and ask the user what to do with each — continue, approve, revise, archive, ignore.
            2. Whenever the Persistent Memory block contains unresolved follow-ups, pending commitments, or context the user may have forgotten, mention them and ask whether they are still relevant.
            3. At session start or on ambiguous messages, offer 2–3 concrete next steps grounded in the context and memories available — not a generic greeting.
            4. Never wait passively for the user to ask 'what do I have pending' — bring unfinished state up first.
            5. If there is genuinely no context to act on, ask ONE short open question to discover intent.

            Planning intent:
            - If the user's message contains '/plan' anywhere (not only as the first token) OR expresses intent to plan / structure / design something (examples: "let's plan X", "plan for X", "create a plan for X", "help me plan X" — apply the same rule when the user expresses this intent in any other language), call the startPlanning tool with the task as your FIRST action, before any other reply. This puts the session into DISCOVERY phase so the CLI shows the planning toolbar. Only skip the tool if a planning session for the same task is already active in the Session Context.

            Listing plans:
            - When the user asks about their plans in natural language (examples: "show me my plans", "what are my active plans", "list my plans"), call the list_plans tool and then summarise the returned rows as a short text or markdown list IN YOUR REPLY. Do NOT open the interactive picker for these queries — the picker is reserved for the explicit `/plans` slash command.
            - Use each row's `statusLabel` field VERBATIM for the human-readable status. Never relabel a plan's status yourself (for instance, a row whose raw status is 'completed' or 'abandoned' must not be shown with the label of an 'active' plan). If `statusLabel` is missing, fall back to the raw `status` field.
            - If the user asks about ONE specific plan by name or id, prefer `resume_plan` (loads it for detailed discussion) over `list_plans`.""";

    private static final String CONCISE_SUPERVISOR_PROMPT = """
            You are Kukuvaia. Use tools for every factual or action-taking step; never assert results without a tool. Before editing, name the likely target. Prefer minimal changes. Ask one short clarifying question when intent is ambiguous. Say "unknown" rather than invent. On '/plan' or planning intent, call startPlanning tool first.""";

    private void loadPersonaFile(Path path) {
        try {
            PersonaSpec spec = yamlMapper.readValue(path.toFile(), PersonaSpec.class);
            personas.put(spec.name(), spec);
            log.info("Loaded persona '{}' from {}", spec.name(), path.getFileName());
        } catch (IOException e) {
            log.warn("Failed to load persona from {}: {}", path, e.getMessage());
        }
    }
}
