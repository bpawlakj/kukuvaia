package ai.kukuvaia.agent.daemon.def;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.exec.DaemonExecutor;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.extensions.ExtensionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * All daemon job definitions, validated at startup.
 *
 * <p>Sources: built-ins on the classpath ({@code classpath*:daemon/*.yaml}) and — only when
 * {@code kukuvaia.daemon.project-jobs=true} — the project's {@code .kukuvaia/daemon/*.yaml}. Project
 * jobs are a CLI-mode feature (the user's own machine); a server leaves them off so job config stays
 * server-controlled (AGENTS.md trust boundary). A project job with the same name replaces a built-in.
 *
 * <p>Every definition is validated by the executor of its kind ({@link DaemonExecutor#validate}); a
 * kind without an executor (e.g. {@code a2a} before it is implemented) is rejected. Invalid files are
 * logged and skipped — one bad file never prevents startup.
 */
@Component
public class DaemonJobRegistry {

    private static final Logger log = LoggerFactory.getLogger(DaemonJobRegistry.class);

    private final Map<String, DaemonJobDefinition> jobs;
    private final Map<String, DaemonJobDefinition> byWebhookRoute;
    private final List<String> rejected;

    public DaemonJobRegistry(List<DaemonExecutor> executors, DaemonProperties properties, ExtensionLoader extensions) {
        this(executors, loadSources(properties.isProjectJobs() ? extensions.getExtensionRoot() : Optional.empty()));
    }

    /** Test/back-door constructor: definitions already read as (source, yaml) pairs, in precedence order. */
    public DaemonJobRegistry(List<DaemonExecutor> executors, List<Map.Entry<String, String>> sources) {
        Map<JobKind, DaemonExecutor> byKind = new EnumMap<>(JobKind.class);
        executors.forEach(e -> byKind.put(e.kind(), e));
        Map<String, DaemonJobDefinition> loaded = new LinkedHashMap<>();
        Map<String, DaemonJobDefinition> routes = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, String> source : sources) {
            DaemonJobDefinition def;
            try {
                def = DaemonJobParser.parse(source.getValue(), source.getKey());
            } catch (DaemonJobParser.InvalidJobDefinitionException e) {
                errors.add(e.getMessage());
                continue;
            }
            DaemonExecutor executor = byKind.get(def.kind());
            if (executor == null) {
                errors.add(def.source() + ": kind '" + def.kind().dbValue() + "' is not available in this build");
                continue;
            }
            Optional<String> problem = executor.validate(def);
            if (problem.isPresent()) {
                errors.add(def.source() + ": " + problem.get());
                continue;
            }
            // Validate completely before touching the job this one would replace — a rejected
            // replacement must leave the existing (e.g. built-in) job active.
            String clash = webhookClash(def, routes);
            if (clash != null) {
                errors.add(def.source() + ": webhook route '" + clash + "' is already used by job '"
                        + routes.get(clash).name() + "'");
                continue;
            }
            DaemonJobDefinition previous = loaded.remove(def.name());
            if (previous != null) {
                log.info("Daemon job '{}' from {} replaces the one from {}", def.name(), def.source(), previous.source());
                routes.values().removeIf(d -> d.name().equals(def.name()));
            }
            loaded.put(def.name(), def);
            for (DaemonTrigger t : def.triggers()) {
                if (t instanceof DaemonTrigger.Webhook w) routes.put(w.route(), def);
            }
        }
        errors.forEach(e -> log.error("Daemon job definition rejected — {}", e));
        log.info("Daemon jobs loaded: {} active ({}), {} rejected", loaded.size(), loaded.keySet(), errors.size());
        this.jobs = Collections.unmodifiableMap(loaded);
        this.byWebhookRoute = Collections.unmodifiableMap(routes);
        this.rejected = List.copyOf(errors);
    }

    public List<DaemonJobDefinition> all() {
        return List.copyOf(jobs.values());
    }

    public Optional<DaemonJobDefinition> find(String name) {
        return Optional.ofNullable(jobs.get(name));
    }

    public Optional<DaemonJobDefinition> byWebhookRoute(String route) {
        return Optional.ofNullable(byWebhookRoute.get(route));
    }

    /** Jobs and their declared PG NOTIFY channels. */
    public Map<String, List<DaemonJobDefinition>> byPgNotifyChannel() {
        Map<String, List<DaemonJobDefinition>> out = new LinkedHashMap<>();
        for (DaemonJobDefinition def : jobs.values()) {
            for (DaemonTrigger t : def.triggers()) {
                if (t instanceof DaemonTrigger.PgNotify p) out.computeIfAbsent(p.channel(), c -> new ArrayList<>()).add(def);
            }
        }
        return out;
    }

    /** Validation errors of rejected definitions (API / diagnostics). */
    public List<String> rejected() {
        return rejected;
    }

    /** A route already used by a <em>different</em> job (a same-name replacement may reuse its routes). */
    private static String webhookClash(DaemonJobDefinition def, Map<String, DaemonJobDefinition> routes) {
        for (DaemonTrigger t : def.triggers()) {
            if (t instanceof DaemonTrigger.Webhook w && routes.containsKey(w.route())
                    && !routes.get(w.route()).name().equals(def.name())) {
                return w.route();
            }
        }
        return null;
    }

    public static List<Map.Entry<String, String>> loadSources(Optional<Path> projectExtensionRoot) {
        List<Map.Entry<String, String>> sources = new ArrayList<>();
        try {
            Resource[] builtIns = new PathMatchingResourcePatternResolver().getResources("classpath*:daemon/*.yaml");
            java.util.Arrays.sort(builtIns, java.util.Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource r : builtIns) {
                sources.add(Map.entry("classpath:daemon/" + r.getFilename(),
                        new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.error("Could not read built-in daemon jobs: {}", e.getMessage());
        }
        projectExtensionRoot.map(root -> root.resolve("daemon")).filter(Files::isDirectory).ifPresent(dir -> {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().forEach(f -> {
                    try {
                        sources.add(Map.entry(".kukuvaia/daemon/" + f.getFileName(), Files.readString(f)));
                    } catch (IOException e) {
                        log.error("Could not read daemon job {}: {}", f.getFileName(), e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.error("Could not list project daemon jobs: {}", e.getMessage());
            }
        });
        return sources;
    }
}
