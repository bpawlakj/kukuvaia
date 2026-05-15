package ai.kukuvaia.provider.transport;

import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.secret.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides the wire dialect for one (provider, model) pair and packs the answer in a
 * {@link TransportSpec}. Routing is three-tier, highest priority first:
 *
 * <ol>
 *   <li>{@code model.config.transport} — explicit per-model override.</li>
 *   <li>{@code provider.config.transport-rules} — list of {@code {match, transport}}
 *       entries the operator can edit through the admin UI.</li>
 *   <li>Built-in heuristic on the model id (gpt-5* / o-series / gemini-* →
 *       Responses; claude-* → Messages; everything else → Chat Completions).</li>
 * </ol>
 *
 * <p>No provider name is hard-coded — the same factory works for SmartGate,
 * OpenRouter, GitHub Copilot, future Bedrock proxies, etc.
 */
@Component
public class ChatTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatTransportFactory.class);

    private static final String DEFAULT_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String DEFAULT_RESPONSES_PATH = "/v1/responses";
    private static final String DEFAULT_MESSAGES_PATH = "/v1/messages";

    /** Path config-key per transport — kept in one place so admins find them easily. */
    private static final Map<TransportType, String> PATH_CONFIG_KEYS = Map.of(
            TransportType.OPENAI_CHAT_COMPLETIONS, "completions-path",
            TransportType.OPENAI_RESPONSES, "responses-path",
            TransportType.ANTHROPIC_MESSAGES, "messages-path");

    private static final Map<TransportType, String> DEFAULT_PATHS = Map.of(
            TransportType.OPENAI_CHAT_COMPLETIONS, DEFAULT_CHAT_COMPLETIONS_PATH,
            TransportType.OPENAI_RESPONSES, DEFAULT_RESPONSES_PATH,
            TransportType.ANTHROPIC_MESSAGES, DEFAULT_MESSAGES_PATH);

    private final SecretResolver secretResolver;

    public ChatTransportFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public TransportSpec resolve(ProviderRecord provider, ModelRecord model) {
        TransportType type = resolveType(provider, model);
        TransportSpec.Source source = TransportSpec.Source.HEURISTIC;
        if (typeFromModelConfig(model) != null) source = TransportSpec.Source.MODEL_CONFIG;
        else if (typeFromProviderRules(provider, model) != null) source = TransportSpec.Source.PROVIDER_RULE;

        String baseUrl = normaliseBaseUrl(provider.baseUrl());
        String path = resolvePath(provider, type);
        String apiKey = secretResolver.resolve(provider.apiKeyRef());
        Map<String, String> headers = resolveExtraHeaders(provider.config());

        log.info("[transport] provider={} model={} → type={} url={}{} (source={})",
                provider.name(), model.modelId(), type, baseUrl, path, source);

        return new TransportSpec(type, baseUrl, path, apiKey, headers, source);
    }

    /* ----- routing decision ----- */

    TransportType resolveType(ProviderRecord provider, ModelRecord model) {
        TransportType fromModel = typeFromModelConfig(model);
        if (fromModel != null) return fromModel;
        TransportType fromRules = typeFromProviderRules(provider, model);
        if (fromRules != null) return fromRules;
        return heuristic(model.modelId());
    }

    private static TransportType typeFromModelConfig(ModelRecord model) {
        if (model.config() == null) return null;
        Object value = model.config().get("transport");
        return value == null ? null : TransportType.fromString(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static TransportType typeFromProviderRules(ProviderRecord provider, ModelRecord model) {
        if (provider.config() == null) return null;
        Object rulesRaw = provider.config().get("transport-rules");
        if (!(rulesRaw instanceof List<?> rules)) return null;

        for (Object entry : rules) {
            if (!(entry instanceof Map<?, ?> rule)) continue;
            Object transport = rule.get("transport");
            if (transport == null) continue;
            TransportType candidate = TransportType.fromString(transport.toString());
            if (candidate == null) continue;
            if (ruleMatches((Map<String, Object>) rule, model.modelId())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean ruleMatches(Map<String, Object> rule, String modelId) {
        Object prefix = rule.get("prefix");
        if (prefix != null && modelId.startsWith(prefix.toString())) return true;
        Object suffix = rule.get("suffix");
        if (suffix != null && modelId.endsWith(suffix.toString())) return true;
        Object contains = rule.get("contains");
        if (contains != null && modelId.contains(contains.toString())) return true;
        Object regex = rule.get("regex");
        if (regex != null) {
            try {
                return modelId.matches(regex.toString());
            } catch (java.util.regex.PatternSyntaxException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Built-in fallback. Conservative — when in doubt, classic Chat Completions, because
     * every OpenAI-compatible provider speaks it.
     */
    static TransportType heuristic(String modelId) {
        if (modelId == null) return TransportType.OPENAI_CHAT_COMPLETIONS;
        String id = modelId.toLowerCase();
        if (id.startsWith("gpt-5") || id.startsWith("gpt-6")) return TransportType.OPENAI_RESPONSES;
        if (id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")) return TransportType.OPENAI_RESPONSES;
        if (id.startsWith("gemini-")) return TransportType.OPENAI_RESPONSES;
        if (id.startsWith("claude-")) return TransportType.ANTHROPIC_MESSAGES;
        return TransportType.OPENAI_CHAT_COMPLETIONS;
    }

    /* ----- URL + headers ----- */

    private static String normaliseBaseUrl(String baseUrl) {
        String url = baseUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/v1")) url = url.substring(0, url.length() - 3);
        return url;
    }

    private String resolvePath(ProviderRecord provider, TransportType type) {
        String configKey = PATH_CONFIG_KEYS.get(type);
        String defaultPath = DEFAULT_PATHS.get(type);
        if (provider.config() == null) return defaultPath;

        // Preferred shape: provider.config.paths.<key>
        Object pathsObj = provider.config().get("paths");
        if (pathsObj instanceof Map<?, ?> paths) {
            Object byType = paths.get(type.name().toLowerCase().replace('_', '-'));
            if (byType != null) return ensureLeadingSlash(byType.toString());
            Object byKey = paths.get(configKey);
            if (byKey != null) return ensureLeadingSlash(byKey.toString());
        }

        // Legacy/back-compat shape: top-level "completions-path"/"responses-path"/"messages-path".
        Object flat = provider.config().get(configKey);
        if (flat != null) {
            String s = flat.toString().trim();
            if (!s.isBlank()) return ensureLeadingSlash(s);
        }
        return defaultPath;
    }

    private static String ensureLeadingSlash(String s) {
        String t = s.trim();
        return t.startsWith("/") ? t : "/" + t;
    }

    static Map<String, String> resolveExtraHeaders(Map<String, Object> config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null) return result;
        Object headers = config.get("headers");
        if (!(headers instanceof Map<?, ?> headerMap)) return result;
        for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toString().trim();
            if (key.isEmpty()) continue;
            result.put(key, entry.getValue().toString());
        }
        return result;
    }

    /** Test-only — expose the package-private path config keys list. */
    static List<String> knownPathConfigKeys() {
        return new ArrayList<>(PATH_CONFIG_KEYS.values());
    }
}
