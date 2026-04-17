package ai.kukuvaia.api;

import ai.kukuvaia.commands.CommandRegistry;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * POST /api/commands/{cmd} — deterministic command dispatch, no LLM.
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandRegistry commandRegistry;

    public CommandController(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    @PostMapping("/{cmd}")
    public ResponseEntity<List<OutputBlock>> executeCommand(
            @PathVariable String cmd,
            @RequestBody(required = false) Map<String, String> body) {
        String args = body != null ? body.getOrDefault("args", "") : "";
        String sessionId = body != null ? body.getOrDefault("sessionId", "default") : "default";

        return commandRegistry.resolve(cmd)
                .map(command -> ResponseEntity.ok(command.execute(args, sessionId)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(List.of(new TextBlock("Unknown command: /" + cmd, "error"))));
    }
}
