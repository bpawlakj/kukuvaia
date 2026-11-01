package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.provider.model.ProviderRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM provider service — facade for model resolution.
 * Delegates to {@link ChatModelCache} for config-backed model lookup.
 * Falls back to manually registered providers for backward compatibility.
 *
 * <p>Interactive resolution order:
 * <ol>
 *   <li>Try as role name ("advisor", "worker", "supervisor") via ChatModelCache</li>
 *   <li>Try as manually registered provider name (backward compat)</li>
 *   <li>Fallback to "supervisor" role</li>
 * </ol>
 *
 * <p><b>Daemon policy</b> ({@link ExecutionContext#DAEMON}, AGENTS.md "Daemon provider defaults to
 * SmartGate; Copilot is opt-in"): without an explicit provider, unattended work resolves the role
 * named by {@code kukuvaia.daemon.default-provider} (default {@code daemon}, meant to point at
 * SmartGate), falling back to {@code supervisor}. A daemon call that was <em>not</em> explicitly
 * routed to Copilot is refused if it would land on a Copilot provider — fail closed. An explicit
 * provider (job/specialist {@code provider: copilot}) is the deliberate opt-in.
 */
@Component
public class LlmProviderService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);
    private static final String DEFAULT_ROLE = "supervisor";
    static final String DEFAULT_DAEMON_ROLE = "daemon";

    private final ChatModelCache chatModelCache;
    private final String daemonDefaultRole;

    // Backward compat: manually registered providers (e.g., from /login or Spring auto-config)
    private final Map<String, ChatModel> legacyProviders = new ConcurrentHashMap<>();

    @Autowired
    public LlmProviderService(ChatModelCache chatModelCache,
                              @Value("${kukuvaia.daemon.default-provider:" + DEFAULT_DAEMON_ROLE + "}")
                              String daemonDefaultRole) {
        this.chatModelCache = chatModelCache;
        this.daemonDefaultRole = daemonDefaultRole == null || daemonDefaultRole.isBlank()
                ? DEFAULT_DAEMON_ROLE : daemonDefaultRole.trim();
    }

    public LlmProviderService(ChatModelCache chatModelCache) {
        this(chatModelCache, DEFAULT_DAEMON_ROLE);
    }

    /**
     * Register a provider manually (backward compat — used at startup or after /login).
     */
    public void register(String name, ChatModel chatModel) {
        legacyProviders.put(name, chatModel);
        log.info("Registered legacy LLM provider: {}", name);
    }

    /**
     * Resolve a ChatModel. Tries role-based lookup first, then legacy providers, then default.
     * Under {@link ExecutionContext#DAEMON} the daemon policy applies (see class Javadoc).
     *
     * @param explicitProvider role name, provider name, or null for default
     * @param context          execution context (INTERACTIVE or DAEMON)
     * @throws DaemonProviderPolicyException if a daemon call would fall back onto Copilot
     */
    public ChatModel resolve(String explicitProvider, ExecutionContext context) {
        boolean explicit = explicitProvider != null && !explicitProvider.isBlank();
        if (explicit) {
            // 1. Try as role name via config-backed cache
            ChatModel byRole = chatModelCache.getByRole(explicitProvider);
            if (byRole != null) {
                log.debug("Resolved '{}' as role for context {}", explicitProvider, context);
                return byRole;
            }

            // 2. Try as legacy registered provider
            ChatModel legacy = legacyProviders.get(explicitProvider);
            if (legacy != null) {
                log.debug("Resolved '{}' as legacy provider for context {}", explicitProvider, context);
                return legacy;
            }
        }

        if (context == ExecutionContext.DAEMON) {
            return resolveDaemonDefault(explicitProvider);
        }

        // 3. Fallback: "supervisor" role
        ChatModel defaultModel = chatModelCache.getByRole(DEFAULT_ROLE);
        if (defaultModel != null) {
            log.debug("Resolved '{}' role for context {}", DEFAULT_ROLE, context);
            return defaultModel;
        }

        // 4. Last resort: legacy "supervisor" or "default" provider
        ChatModel legacyDefault = legacyDefault();
        if (legacyDefault != null) {
            log.debug("Resolved legacy 'default' provider for context {}", context);
            return legacyDefault;
        }

        throw new ProviderNotAvailableException(explicit ? explicitProvider : DEFAULT_ROLE);
    }

    /**
     * Resolve a ChatModel by routing role directly (interactive semantics).
     *
     * @param role the role name (e.g., "advisor", "worker", "supervisor")
     * @return the ChatModel for the role
     * @throws ProviderNotAvailableException if role not assigned
     */
    public ChatModel resolveByRole(String role) {
        ChatModel model = chatModelCache.getByRole(role);
        if (model == null) {
            throw new ProviderNotAvailableException(role);
        }
        return model;
    }

    /**
     * Resolve a ChatModel by role for the given execution context. A role taken from specialist
     * configuration ({@code tier:}) is not an explicit opt-in, so under DAEMON a Copilot-backed role
     * is refused.
     */
    public ChatModel resolveByRole(String role, ExecutionContext context) {
        ChatModel model = resolveByRole(role);
        if (context == ExecutionContext.DAEMON) {
            refuseCopilot(role, "tier '" + role + "'");
        }
        return model;
    }

    /**
     * Get all ChatModels assigned to worker roles (worker, worker-2, worker-3, ...).
     * Used for round-robin distribution in parallel worker execution.
     *
     * @return list of worker models (may be empty if no workers configured)
     */
    public List<ChatModel> resolveWorkerModels() {
        return chatModelCache.getWorkerModels();
    }

    public boolean isAvailable(String name) {
        return chatModelCache.getByRole(name) != null || legacyProviders.containsKey(name);
    }

    /** The role unattended work defaults to ({@code kukuvaia.daemon.default-provider}). */
    public String daemonDefaultRole() {
        return daemonDefaultRole;
    }

    // ── Daemon policy ────────────────────────────────────────────────────────

    private ChatModel resolveDaemonDefault(String requested) {
        if (requested != null && !requested.isBlank()) {
            log.warn("Daemon provider '{}' is not configured — falling back to the daemon default role '{}'",
                    requested, daemonDefaultRole);
        }
        ChatModel daemonModel = chatModelCache.getByRole(daemonDefaultRole);
        if (daemonModel != null) {
            refuseCopilot(daemonDefaultRole, "daemon default role '" + daemonDefaultRole + "'");
            log.debug("Resolved daemon default role '{}'", daemonDefaultRole);
            return daemonModel;
        }
        ChatModel supervisor = chatModelCache.getByRole(DEFAULT_ROLE);
        if (supervisor != null) {
            refuseCopilot(DEFAULT_ROLE, "supervisor fallback (no '" + daemonDefaultRole + "' role configured)");
            log.info("No '{}' role configured — daemon work uses the supervisor role", daemonDefaultRole);
            return supervisor;
        }
        // Legacy providers carry no provider metadata, so they cannot be policy-checked. No production
        // code registers them today (register() has no callers outside tests); kept for back-compat.
        ChatModel legacyDefault = legacyDefault();
        if (legacyDefault != null) {
            return legacyDefault;
        }
        throw new ProviderNotAvailableException(daemonDefaultRole);
    }

    private void refuseCopilot(String role, String how) {
        Optional<ProviderRecord> provider = chatModelCache.getProviderForRole(role);
        if (provider.isPresent() && isCopilot(provider.get())) {
            throw new DaemonProviderPolicyException(how);
        }
    }

    private ChatModel legacyDefault() {
        ChatModel legacyDefault = legacyProviders.get("supervisor");
        return legacyDefault != null ? legacyDefault : legacyProviders.get("default");
    }

    /** Copilot = provider name/type "copilot" or a base URL on githubcopilot.com. */
    static boolean isCopilot(ProviderRecord provider) {
        if (isCopilotName(provider.name()) || isCopilotName(provider.type())) return true;
        String baseUrl = provider.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host != null && (host.equalsIgnoreCase("githubcopilot.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".githubcopilot.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isCopilotName(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).contains("copilot");
    }

    public static class ProviderNotAvailableException extends RuntimeException {
        public ProviderNotAvailableException(String provider) {
            super("LLM provider '%s' not configured or not authenticated".formatted(provider));
        }
    }

    /** A daemon call would have used Copilot without an explicit opt-in. */
    public static class DaemonProviderPolicyException extends RuntimeException {
        public DaemonProviderPolicyException(String how) {
            super(("Daemon work must not run on GitHub Copilot implicitly (%s resolves to a Copilot provider). "
                    + "Configure the daemon role for SmartGate, or set provider: copilot explicitly on the job.")
                    .formatted(how));
        }
    }
}
