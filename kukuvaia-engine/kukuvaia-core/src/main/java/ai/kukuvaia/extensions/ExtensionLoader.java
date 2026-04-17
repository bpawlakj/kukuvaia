package ai.kukuvaia.extensions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Discovers .kukuvaia/ project directory by walking up from CWD.
 * Entry point for user extensibility: rules, skills, commands, personas.
 */
@Component
public class ExtensionLoader {

    private static final Logger log = LoggerFactory.getLogger(ExtensionLoader.class);
    private static final String EXTENSION_DIR = ".kukuvaia";

    private final Path extensionRoot;

    public ExtensionLoader() {
        this.extensionRoot = discover().orElse(null);
        if (extensionRoot != null) {
            log.info("Extension directory found: {}", extensionRoot);
        } else {
            log.info("No .kukuvaia/ directory found — user extensions disabled");
        }
    }

    public Optional<Path> getExtensionRoot() {
        return Optional.ofNullable(extensionRoot);
    }

    public Optional<Path> getRulesDir() {
        return getSubDir("rules");
    }

    public Optional<Path> getSkillsDir() {
        return getSubDir("skills");
    }

    public Optional<Path> getCommandsDir() {
        return getSubDir("commands");
    }

    public Optional<Path> getPersonasDir() {
        return getSubDir("personas");
    }

    private Optional<Path> getSubDir(String name) {
        if (extensionRoot == null) return Optional.empty();
        Path dir = extensionRoot.resolve(name);
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    private Optional<Path> discover() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(EXTENSION_DIR);
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }
}
