package ai.kukuvaia.tools;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.WorkerResult;
import ai.kukuvaia.agent.subagent.WorkerTask;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Delegation tools for the supervisor agent.
 * Allows the main ChatClient to delegate tasks to specialist sub-agents.
 *
 * These tools are blocked for sub-agents via SubAgentGuard.BLOCKED_TOOLS,
 * preventing recursive delegation.
 */
@Component
public class DelegationTools {

    public static final String PERSONA_TOOLS_CONTEXT_KEY = "kukuvaia.personaTools";

    private static final Logger log = LoggerFactory.getLogger(DelegationTools.class);

    private final SubAgentFactory subAgentFactory;
    private final ObjectMapper objectMapper;

    public DelegationTools(SubAgentFactory subAgentFactory, ObjectMapper objectMapper) {
        this.subAgentFactory = subAgentFactory;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Delegate a task to a specialist sub-agent. Use for tasks that require a specific specialist skill. Returns the specialist's response.")
    public String delegate_to_specialist(
            @ToolParam(description = "Task description for the specialist") String task,
            @ToolParam(description = "Specialist type (e.g., 'analyst', 'summarizer')") String specialistType,
            ToolContext toolContext) {
        log.info("delegate_to_specialist: specialist={}, taskLength={}", specialistType, task.length());
        return subAgentFactory.execute(task, specialistType, ExecutionContext.INTERACTIVE, null,
                0, personaTools(toolContext));
    }

    @Tool(description = "Delegate multiple tasks to workers in parallel. Each task runs concurrently on a separate worker. Input: JSON array of objects with 'task' and 'specialistType' fields. Returns JSON array of results with status, result, and durationMs per worker.")
    public String delegate_to_workers(
            @ToolParam(description = "JSON array of worker tasks, e.g. [{\"task\":\"analyze X\",\"specialistType\":\"analyst\"},{\"task\":\"summarise Y\",\"specialistType\":\"summarizer\"}]") String tasksJson,
            ToolContext toolContext) {
        log.info("delegate_to_workers: parsing {} chars of task JSON", tasksJson.length());
        Set<String> allowedTools = personaTools(toolContext);

        try {
            List<Map<String, String>> taskMaps = objectMapper.readValue(tasksJson,
                    new TypeReference<>() {});

            List<WorkerTask> tasks = taskMaps.stream()
                    .map(m -> new WorkerTask(m.get("task"), m.get("specialistType")))
                    .toList();

            List<WorkerResult> results = subAgentFactory.executeParallel(
                    tasks, ExecutionContext.INTERACTIVE, null, allowedTools);

            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            log.error("delegate_to_workers failed: {}", e.getMessage());
            return "Delegation failed: " + e.getMessage();
        }
    }

    private static Set<String> personaTools(ToolContext context) {
        if (context == null || !(context.getContext().get(PERSONA_TOOLS_CONTEXT_KEY) instanceof Set<?> tools)
                || tools.stream().anyMatch(tool -> !(tool instanceof String))) {
            throw new IllegalStateException("Delegation requires a trusted parent tool scope");
        }
        return tools.stream().map(String.class::cast).collect(Collectors.toUnmodifiableSet());
    }
}
