package ai.kukuvaia.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of loaded skills indexed by name.
 * Supports resolution by name or trigger.
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillSpec> skills = new ConcurrentHashMap<>();

    public SkillRegistry(SkillsLoader skillsLoader) {
        for (SkillSpec spec : skillsLoader.loadSkills()) {
            skills.put(spec.name(), spec);
        }
        log.info("Skill registry initialized with {} skills: {}", skills.size(), skills.keySet());
    }

    public Optional<SkillSpec> resolve(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Optional<SkillSpec> resolveByTrigger(String trigger) {
        return skills.values().stream()
                .filter(s -> trigger.equals(s.trigger()))
                .findFirst();
    }

    public Map<String, SkillSpec> allSkills() {
        return Collections.unmodifiableMap(skills);
    }
}
