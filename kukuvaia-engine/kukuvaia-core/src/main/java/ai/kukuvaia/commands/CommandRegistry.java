package ai.kukuvaia.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for slash commands. Dispatches by exact name match.
 */
@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, SlashCommand> commands = new ConcurrentHashMap<>();

    public CommandRegistry(Collection<SlashCommand> slashCommands) {
        for (SlashCommand cmd : slashCommands) {
            commands.put(cmd.name(), cmd);
        }
        log.info("Command registry initialized with {} commands: {}",
                commands.size(), commands.keySet());
    }

    public Optional<SlashCommand> resolve(String commandName) {
        return Optional.ofNullable(commands.get(commandName));
    }

    public Map<String, SlashCommand> allCommands() {
        return Collections.unmodifiableMap(commands);
    }
}
