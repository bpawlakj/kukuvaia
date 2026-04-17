package ai.kukuvaia.extensions;

import ai.kukuvaia.commands.SlashCommand;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads user-defined slash commands from .kukuvaia/commands/*.yaml.
 * Command types: prompt (LLM), shell (subprocess — disabled in web API mode), skill (activate skill).
 */
@Component
public class UserCommandLoader {

    private static final Logger log = LoggerFactory.getLogger(UserCommandLoader.class);

    private final ExtensionLoader extensionLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final List<SlashCommand> userCommands = new ArrayList<>();

    public UserCommandLoader(ExtensionLoader extensionLoader) {
        this.extensionLoader = extensionLoader;
        loadUserCommands();
    }

    public List<SlashCommand> getUserCommands() {
        return List.copyOf(userCommands);
    }

    private void loadUserCommands() {
        extensionLoader.getCommandsDir().ifPresent(dir -> {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                        .forEach(this::loadCommandFile);
            } catch (IOException e) {
                log.warn("Failed to list commands directory: {}", e.getMessage());
            }
        });
        log.info("Loaded {} user commands", userCommands.size());
    }

    @SuppressWarnings("unchecked")
    private void loadCommandFile(Path path) {
        try {
            Map<String, Object> def = yamlMapper.readValue(path.toFile(), Map.class);
            String name = (String) def.get("name");
            String description = (String) def.getOrDefault("description", "User command");
            String type = (String) def.getOrDefault("type", "prompt");
            String template = (String) def.getOrDefault("template", "");

            if (name == null || name.isBlank()) {
                log.warn("Skipping command without name: {}", path);
                return;
            }

            userCommands.add(new SlashCommand() {
                @Override public String name() { return name; }
                @Override public String description() { return description; }
                @Override public List<OutputBlock> execute(String args, String sessionId) {
                    return List.of(new TextBlock(
                            "User command '%s' (type=%s): %s".formatted(name, type, template), null));
                }
            });
            log.info("Loaded user command '{}' (type={}) from {}", name, type, path.getFileName());
        } catch (IOException e) {
            log.warn("Failed to load command from {}: {}", path, e.getMessage());
        }
    }
}
