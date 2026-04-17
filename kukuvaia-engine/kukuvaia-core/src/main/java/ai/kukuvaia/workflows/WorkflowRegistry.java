package ai.kukuvaia.workflows;

import ai.kukuvaia.output.OutputBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for structured workflows (Tier 2 routing).
 * Workflows are fixed pipelines — deterministic steps with 1 LLM call maximum.
 *
 * Three-tier routing: /command → CommandRouter → deterministic (Tier 1)
 *                     /workflow → WorkflowRegistry → fixed pipeline (Tier 2)
 *                     free text → ChatClient + tools (Tier 3)
 */
@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final Map<String, Workflow> workflows = new ConcurrentHashMap<>();

    public WorkflowRegistry(Collection<Workflow> workflowBeans) {
        for (Workflow wf : workflowBeans) {
            workflows.put(wf.name(), wf);
        }
        log.info("Workflow registry initialized with {} workflows: {}", workflows.size(), workflows.keySet());
    }

    public Optional<Workflow> resolve(String name) {
        return Optional.ofNullable(workflows.get(name));
    }

    public Map<String, Workflow> allWorkflows() {
        return Collections.unmodifiableMap(workflows);
    }

    /**
     * Interface for structured workflows.
     */
    public interface Workflow {
        String name();
        String description();
        List<OutputBlock> execute(String args, String sessionId);
    }
}
