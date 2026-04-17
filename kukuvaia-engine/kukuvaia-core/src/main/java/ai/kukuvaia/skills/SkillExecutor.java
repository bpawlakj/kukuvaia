package ai.kukuvaia.skills;

import ai.kukuvaia.agent.AgentService;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.scripting.LuaSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Executes skills by dispatching to LLM (prompt) or Lua sandbox (script).
 */
@Service
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private final AgentService agentService;
    private final LuaSandbox luaSandbox;

    public SkillExecutor(AgentService agentService) {
        this.agentService = agentService;
        this.luaSandbox = new LuaSandbox();
    }

    public List<OutputBlock> execute(SkillSpec skill, String args, String sessionId) {
        log.info("Executing skill '{}' (type={}) with args='{}'", skill.name(), skill.type(), args);

        return switch (skill.type()) {
            case PROMPT -> executePrompt(skill, args, sessionId);
            case SCRIPT -> executeScript(skill, args);
        };
    }

    private List<OutputBlock> executePrompt(SkillSpec skill, String args, String sessionId) {
        String message = "## Active Skill: %s\n\n%s\n\n## User Request\nExecute with args: %s"
                .formatted(skill.name(), skill.body(), args);
        String response = agentService.chat(sessionId, message);
        return List.of(new TextBlock(response, null));
    }

    private List<OutputBlock> executeScript(SkillSpec skill, String args) {
        try {
            Map<String, Object> bindings = Map.of("args", args);
            Object result = luaSandbox.execute(skill.scriptSource(), bindings, skill.timeoutMillis());
            return List.of(new TextBlock(String.valueOf(result), null));
        } catch (LuaSandbox.LuaTimeoutException e) {
            return List.of(new TextBlock("Skill '%s' timed out".formatted(skill.name()), "error"));
        } catch (Exception e) {
            return List.of(new TextBlock("Skill failed: " + e.getMessage(), "error"));
        }
    }
}
