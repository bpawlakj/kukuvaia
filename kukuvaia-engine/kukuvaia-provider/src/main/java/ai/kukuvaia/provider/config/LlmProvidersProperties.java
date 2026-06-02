package ai.kukuvaia.provider.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File-based LLM provider configuration loaded via
 * {@code @ConfigurationProperties(prefix = "kukuvaia.llm-providers")}.
 *
 * <p>Replaces the DB-backed provider/model/role tables. Full config lives in
 * {@code application.yaml} (or overridden via env vars). Secrets ({@code apiKeyRef})
 * are resolved by {@link ai.kukuvaia.provider.secret.EnvVarSecretResolver}: a value
 * matching {@code ^[A-Za-z_][A-Za-z0-9_]*$} is treated as an env-var name; anything
 * else (e.g. a literal {@code sk-ant-...} key) is used verbatim.
 *
 * <p>Example {@code application.yaml} block:
 * <pre>
 * kukuvaia:
 *   llm-providers:
 *     providers:
 *       - name: anthropic
 *         type: anthropic
 *         baseUrl: https://api.anthropic.com
 *         apiKeyRef: ${ANTHROPIC_API_KEY:}
 *         enabled: true
 *         models:
 *           - modelId: claude-sonnet-4-6
 *             displayName: Claude Sonnet 4.6
 *             tier: standard
 *             maxTokens: 8096
 *             capabilities: [chat, tools]
 *     roles:
 *       supervisor: claude-sonnet-4-6
 *       advisor: claude-sonnet-4-6
 *       worker: claude-sonnet-4-6
 * </pre>
 */
public class LlmProvidersProperties {

    private List<ProviderDef> providers = new ArrayList<>();

    /** Maps role name (e.g. "supervisor") to a modelId string (e.g. "claude-sonnet-4-6"). */
    private Map<String, String> roles = new HashMap<>();

    public List<ProviderDef> getProviders() { return providers; }
    public void setProviders(List<ProviderDef> providers) { this.providers = providers; }

    public Map<String, String> getRoles() { return roles; }
    public void setRoles(Map<String, String> roles) { this.roles = roles; }

    /** Returns true when at least one provider with a model is defined. */
    public boolean isConfigured() {
        return providers != null && providers.stream().anyMatch(p -> !p.getModels().isEmpty());
    }

    // ── Provider definition ──────────────────────────────────────────────────

    public static class ProviderDef {
        private String name;
        private String type;
        private String baseUrl;
        private String apiKeyRef;
        private boolean enabled = true;
        private Map<String, Object> config = new HashMap<>();
        private List<ModelDef> models = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKeyRef() { return apiKeyRef; }
        public void setApiKeyRef(String apiKeyRef) { this.apiKeyRef = apiKeyRef; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }

        public List<ModelDef> getModels() { return models; }
        public void setModels(List<ModelDef> models) { this.models = models; }
    }

    // ── Model definition ─────────────────────────────────────────────────────

    public static class ModelDef {
        private String modelId;
        private String displayName;
        private String tier = "standard";
        private int maxTokens = 4096;
        /** Optional: context window size in tokens. Used by ContextCompactionAdvisor. */
        private Integer contextWindow;
        private List<String> capabilities = new ArrayList<>();
        private Map<String, Object> config = new HashMap<>();

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }

        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }
    }
}
