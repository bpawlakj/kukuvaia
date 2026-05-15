package ai.kukuvaia.api;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.commands.CommandRegistry;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * POST /api/commands/{cmd} — deterministic command dispatch, no LLM.
 *
 * <p>Body may carry an optional {@code persona} key; when present the session is (re)bound to
 * that persona before the command runs. Same contract as ChatController — unknown personas
 * yield a 400 with an error TextBlock so a typo in {@code KUKUVAIA_PERSONA} is visible.
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandRegistry commandRegistry;
    private final PersonaService personaService;

    public CommandController(CommandRegistry commandRegistry, PersonaService personaService) {
        this.commandRegistry = commandRegistry;
        this.personaService = personaService;
    }

    @PostMapping("/{cmd}")
    public ResponseEntity<List<OutputBlock>> executeCommand(
            @PathVariable String cmd,
            @RequestBody(required = false) Map<String, String> body) {
        String args = body != null ? body.getOrDefault("args", "") : "";
        String sessionId = body != null ? body.getOrDefault("sessionId", "default") : "default";
        String persona = body != null ? body.get("persona") : null;

        try {
            personaService.applyIfPresent(sessionId, persona);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(List.of(new TextBlock("Unknown persona: " + persona, "error")));
        }

        return commandRegistry.resolve(cmd)
                .map(command -> ResponseEntity.ok(command.execute(args, sessionId)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(List.of(new TextBlock("Unknown command: /" + cmd, "error"))));
    }
}
