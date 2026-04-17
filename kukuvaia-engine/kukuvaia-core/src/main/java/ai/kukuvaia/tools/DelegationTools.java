package ai.kukuvaia.tools;

import ai.kukuvaia.agent.daemon.ExecutionContext;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.WorkerResult;
import ai.kukuvaia.agent.subagent.WorkerTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Delegation tools for the supervisor agent.
 * Allows the main ChatClient to delegate tasks to specialist sub-agents.
 *
 * These tools are blocked for sub-agents via SubAgentGuard.BLOCKED_TOOLS,
 * preventing recursive delegation.
 */
@Component
public class DelegationTools {

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
            @ToolParam(description = "Specialist type (e.g., 'analyst', 'validator')") String specialistType) {
        log.info("delegate_to_specialist: specialist={}, taskLength={}", specialistType, task.length());
        return subAgentFactory.execute(task, specialistType, ExecutionContext.INTERACTIVE, null);
    }

    @Tool(description = "Delegate multiple tasks to workers in parallel. Each task runs concurrently on a separate worker. Input: JSON array of objects with 'task' and 'specialistType' fields. Returns JSON array of results with status, result, and durationMs per worker.")
    public String delegate_to_workers(
            @ToolParam(description = "JSON array of worker tasks, e.g. [{\"task\":\"analyze X\",\"specialistType\":\"analyst\"},{\"task\":\"validate Y\",\"specialistType\":\"validator\"}]") String tasksJson) {
        log.info("delegate_to_workers: parsing {} chars of task JSON", tasksJson.length());

        try {
            List<Map<String, String>> taskMaps = objectMapper.readValue(tasksJson,
                    new TypeReference<>() {});

            List<WorkerTask> tasks = taskMaps.stream()
                    .map(m -> new WorkerTask(m.get("task"), m.get("specialistType")))
                    .toList();

            List<WorkerResult> results = subAgentFactory.executeParallel(
                    tasks, ExecutionContext.INTERACTIVE, null, null);

            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            log.error("delegate_to_workers failed: {}", e.getMessage());
            return "Delegation failed: " + e.getMessage();
        }
    }
}
