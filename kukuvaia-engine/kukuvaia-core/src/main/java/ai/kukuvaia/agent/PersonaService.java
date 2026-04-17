package ai.kukuvaia.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads persona definitions from classpath and user .kukuvaia/ directory.
 * Manages active persona per session.
 */
@Service
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);
    private static final String DEFAULT_PERSONA = "assistant";

    private final Map<String, PersonaSpec> personas = new ConcurrentHashMap<>();
    private final Map<String, String> activePersonaBySession = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public PersonaService() {
        loadBuiltInPersonas();
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

    public Map<String, PersonaSpec> allPersonas() {
        return Map.copyOf(personas);
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
        log.info("Loaded {} built-in persona(s)", personas.size());
    }

    private PersonaSpec createDefaultPersona() {
        return new PersonaSpec(
                DEFAULT_PERSONA,
                "General-purpose AI assistant",
                """
                        You are Kukuvaia, an intelligent AI assistant. Help the user with their requests.

                        When the user's phrasing has more than one plausible meaning in context, ask a short clarifying question before acting. Prefer one targeted question over guessing, and never invent facts the user did not state.""",
                List.of()
        );
    }

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
