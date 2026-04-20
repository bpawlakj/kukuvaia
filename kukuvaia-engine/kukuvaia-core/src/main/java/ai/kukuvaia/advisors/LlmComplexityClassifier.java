package ai.kukuvaia.advisors;

import ai.kukuvaia.provider.registry.ChatModelCache;
import ai.kukuvaia.provider.registry.TaskComplexity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Worker-tier LLM fallback classifier for {@link TaskComplexity} when
 * {@link StructuralHeuristics} confidence is low.
 *
 * Caches per sha256(message) for 5 minutes to amortise cost across repeats
 * (retries, follow-ups with identical wording). Bounded size; simple
 * lazy eviction on write.
 *
 * Timeout is 3s — if the worker model misbehaves the caller falls back to
 * heuristic top instead of blocking the user's turn.
 */
@Component
public class LlmComplexityClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmComplexityClassifier.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_CACHE_ENTRIES = 500;

    private static final String SYSTEM_PROMPT = """
            Classify the user message into exactly one category:
            EXTRACTION, TRANSFORMATION, CLASSIFICATION, RETRIEVAL,
            ANALYSIS, GENERATION, SYNTHESIS, STRATEGY, EVALUATION.
            Return only the category name.
            """;

    private final ChatModelCache chatModelCache;
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public LlmComplexityClassifier(ChatModelCache chatModelCache) {
        this.chatModelCache = chatModelCache;
    }

    public Optional<TaskComplexity> classify(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String key = sha256(message);
        CachedEntry cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            log.debug("LlmComplexityClassifier cache hit: {}... → {}", keyPrefix(key), cached.complexity);
            return Optional.of(cached.complexity);
        }
        if (cached != null) {
            cache.remove(key);
        }

        ChatModel model = chatModelCache.getByRole("worker");
        if (model == null) {
            log.warn("LlmComplexityClassifier: no 'worker' role model available; skipping fallback");
            return Optional.empty();
        }

        try {
            CompletableFuture<TaskComplexity> future = CompletableFuture.supplyAsync(
                    () -> callModel(model, message));
            TaskComplexity result = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result != null) {
                storeCached(key, result);
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (TimeoutException e) {
            log.warn("LlmComplexityClassifier timeout after {} ms", TIMEOUT.toMillis());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("LlmComplexityClassifier error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private TaskComplexity callModel(ChatModel model, String message) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(message)
        ));
        String response = model.call(prompt).getResult().getOutput().getText();
        if (response == null) {
            return null;
        }
        String cleaned = response.trim().toUpperCase();
        for (TaskComplexity c : TaskComplexity.values()) {
            if (cleaned.contains(c.name())) {
                return c;
            }
        }
        log.debug("LlmComplexityClassifier: unparseable response '{}'",
                cleaned.substring(0, Math.min(100, cleaned.length())));
        return null;
    }

    private void storeCached(String key, TaskComplexity value) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expired());
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.keySet().stream().findAny().ifPresent(cache::remove);
            }
        }
        cache.put(key, new CachedEntry(value, Instant.now().plus(CACHE_TTL)));
    }

    /** Visible for tests. */
    int cacheSize() {
        return cache.size();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String keyPrefix(String key) {
        return key.substring(0, Math.min(12, key.length()));
    }

    private record CachedEntry(TaskComplexity complexity, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
