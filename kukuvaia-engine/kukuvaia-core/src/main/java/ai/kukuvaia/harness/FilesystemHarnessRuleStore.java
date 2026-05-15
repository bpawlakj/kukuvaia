package ai.kukuvaia.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Default {@link MutableHarnessRuleStore} backed by a configurable directory of {@code *.md} files.
 *
 * <p>The directory is operator-supplied — typically mounted as a volume in production
 * ({@code /etc/kukuvaia/harness/}) so harness rules live separately from the deployable jar.
 * For local dev it defaults to {@code ./harness/} relative to the working directory; the directory
 * is auto-created on startup so a fresh checkout doesn't fail to boot when no rules exist yet.
 *
 * <p>Names are derived from filenames without the {@code .md} extension. Subdirectories are
 * NOT supported — keeping the namespace flat means the admin panel can list / address rules with
 * a single string identifier and there's no traversal-attack surface to defend.
 *
 * <p>Caching is delegated to {@link HarnessService} — this class always reads from disk so an
 * external editor change is picked up immediately on next resolve (subject to the resolver's
 * own TTL).
 */
@Component
public class FilesystemHarnessRuleStore implements MutableHarnessRuleStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemHarnessRuleStore.class);
    private static final String EXTENSION = ".md";

    /** Filename whitelist — alphanumerics, dash, underscore. Prevents path traversal and weird names. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path directory;

    public FilesystemHarnessRuleStore(@Value("${kukuvaia.harness.directory:./harness}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
            log.info("FilesystemHarnessRuleStore reading from {}", this.directory);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create harness rules directory at " + this.directory, e);
        }
    }

    @Override
    public List<HarnessRule> loadAll() {
        List<HarnessRule> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .forEach(p -> {
                        String name = stripExtension(p);
                        try {
                            String raw = Files.readString(p, StandardCharsets.UTF_8);
                            out.add(HarnessRuleParser.parse(name, raw));
                        } catch (IOException | IllegalArgumentException e) {
                            // One bad file shouldn't poison the whole resolve. Skip + log.
                            log.warn("Skipping malformed harness rule {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list harness directory " + directory, e);
        }
        return out;
    }

    @Override
    public Optional<String> readRaw(String name) {
        Path file = pathFor(name);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read harness rule " + name, e);
        }
    }

    @Override
    public List<String> listNames() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .map(FilesystemHarnessRuleStore::stripExtension)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list harness directory", e);
        }
    }

    @Override
    public HarnessRule save(String name, String rawMarkdown) {
        validateName(name);
        // Parse first — if the body is malformed we want to fail before touching the filesystem.
        HarnessRule parsed = HarnessRuleParser.parse(name, rawMarkdown);
        Path file = pathFor(name);
        try {
            Files.writeString(file, rawMarkdown, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Saved harness rule '{}' ({} bytes)", name, rawMarkdown.length());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write harness rule " + name, e);
        }
        return parsed;
    }

    @Override
    public boolean delete(String name) {
        validateName(name);
        Path file = pathFor(name);
        try {
            boolean removed = Files.deleteIfExists(file);
            if (removed) log.info("Deleted harness rule '{}'", name);
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete harness rule " + name, e);
        }
    }

    private Path pathFor(String name) {
        validateName(name);
        Path resolved = directory.resolve(name + EXTENSION).normalize();
        // Defence-in-depth — VALID_NAME already excludes path separators, but reject anything
        // that escapes the configured directory in case the regex is loosened later.
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("Invalid rule name (resolves outside store): " + name);
        }
        return resolved;
    }

    private static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid rule name: '" + name + "' (must match " + VALID_NAME.pattern() + ")");
        }
    }

    private static String stripExtension(Path p) {
        String fn = p.getFileName().toString();
        return fn.substring(0, fn.length() - EXTENSION.length());
    }
}
