package ai.kukuvaia.extensions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches .kukuvaia/tools/ directory for file changes and triggers hot reload.
 * New tools, modified TOOL.md, or updated config.yaml — all detected automatically.
 * Debounces rapid changes (500ms) to avoid reload storms.
 */
@Component
public class ToolWatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolWatcher.class);
    private static final long DEBOUNCE_MS = 500;

    private final ExtensionLoader extensionLoader;
    private final ToolLoader toolLoader;
    private final ScriptToolCallbackProvider callbackProvider;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public ToolWatcher(ExtensionLoader extensionLoader, ToolLoader toolLoader,
                       ScriptToolCallbackProvider callbackProvider) {
        this.extensionLoader = extensionLoader;
        this.toolLoader = toolLoader;
        this.callbackProvider = callbackProvider;
    }

    @PostConstruct
    public void start() {
        var toolsDir = extensionLoader.getExtensionRoot()
                .map(root -> root.resolve("tools"));

        if (toolsDir.isEmpty() || !Files.isDirectory(toolsDir.get())) {
            log.info("No tools directory to watch");
            return;
        }

        running.set(true);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ToolWatcher");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> watchDirectory(toolsDir.get()));
        log.info("Tool watcher started on: {}", toolsDir.get());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void watchDirectory(Path toolsDir) {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            // Register tools dir and all subdirectories
            registerRecursive(toolsDir, watcher);

            long lastReload = 0;

            while (running.get()) {
                WatchKey key = watcher.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (key == null) continue;

                boolean shouldReload = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    String filename = changed.getFileName().toString();

                    if (filename.endsWith(".md") || filename.endsWith(".yaml") || filename.endsWith(".yml")) {
                        log.debug("Tool file changed: {} ({})", filename, event.kind());
                        shouldReload = true;
                    }

                    // Register new subdirectories
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        Path full = ((Path) key.watchable()).resolve(changed);
                        if (Files.isDirectory(full)) {
                            try {
                                full.register(watcher,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_MODIFY,
                                        StandardWatchEventKinds.ENTRY_DELETE);
                            } catch (IOException ignored) {}
                        }
                    }
                }

                key.reset();

                // Debounce: reload only if enough time passed since last reload
                if (shouldReload) {
                    long now = System.currentTimeMillis();
                    if (now - lastReload > DEBOUNCE_MS) {
                        lastReload = now;
                        log.info("Tool change detected — reloading tools...");
                        toolLoader.reload();
                        callbackProvider.refreshCallbacks();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Tool watcher failed: {}", e.getMessage());
        }
    }

    private void registerRecursive(Path dir, WatchService watcher) throws IOException {
        dir.register(watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        try (var dirs = Files.list(dir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(sub -> {
                        try { sub.register(watcher,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        } catch (IOException ignored) {}
                    });
        }
    }
}
