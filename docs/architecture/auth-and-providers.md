# LLM Authentication & Providers — Kukuvaia

## Overview

Kukuvaia supports multiple LLM providers. Users authenticate via `/login` and select models via `/model`. Both providers expose OpenAI-compatible APIs — Spring AI `OpenAiApi` works for both with different `baseUrl` and `apiKey`.

## Providers

| Provider | API Endpoint | Auth Method | Models |
|----------|-------------|-------------|--------|
| **GitHub Copilot** | `api.githubcopilot.com` | OAuth Device Flow → Copilot token | GPT-4o, Claude Sonnet/Opus, Gemini, etc. |
| **SmartGate** | `{host}/api/ai` | JWT (team-based) | Claude Sonnet (Sanoma-managed) |

Priority: GitHub Copilot first (wider model selection, personal subscription). SmartGate as fallback (corporate, no personal account needed).

---

## Provider 1: GitHub Copilot

### Authentication Flow

Three-step OAuth Device Flow:

```
1. kukuvaia → GitHub:  POST /login/device/code
                       client_id: "Iv1.b507a08c87ecfe98"
                       scope: "read:user"
                       
   ← Returns: device_code, user_code (XXXX-XXXX), verification_uri

2. User opens browser → github.com/login/device → enters user_code → authorizes

3. kukuvaia polls:     POST /login/oauth/access_token
                       client_id, device_code, grant_type
                       
   ← Returns: GitHub OAuth token (gho_xxx)

4. kukuvaia exchanges: GET api.github.com/copilot_internal/v2/token
                       Authorization: token gho_xxx
                       
   ← Returns: Copilot API token (25-minute lifespan)
```

### Key Details

- **Client ID** `Iv1.b507a08c87ecfe98` — GitHub Copilot's public app identifier, same as VS Code/JetBrains use
- **Copilot token expires every 25 minutes** — must auto-refresh in background
- **GitHub OAuth token** — long-lived, stored locally, used to refresh Copilot tokens
- **API is OpenAI-compatible**: `POST api.githubcopilot.com/chat/completions`
- **Also supports Anthropic API**: `POST api.githubcopilot.com/v1/messages` for Claude models (preserves tool_use semantics)

### Available Models (by subscription)

| Tier | Price | Premium Requests/mo | Key Models |
|------|-------|---------------------|------------|
| Free | $0 | 50 | GPT-4.1, Claude Haiku 4.5 |
| Pro | $10/mo | 300 | + Claude Sonnet, Gemini 2.5 Pro |
| Pro+ | $39/mo | 1,500 | + Claude Opus, all models |
| Business | $19/user/mo | 300/user | Similar to Pro |
| Enterprise | $39/user/mo | 1,000/user | All models |

### Model Selection

```
GET api.githubcopilot.com/models
→ {"data": [{"id": "gpt-4o"}, {"id": "claude-sonnet-4.5"}, ...]}

POST api.githubcopilot.com/chat/completions
{"model": "claude-sonnet-4.5", "messages": [...]}
```

### Required Headers

```
Authorization: Bearer {copilot_token}
Content-Type: application/json
Copilot-Integration-Id: kukuvaia
```

---

## Provider 2: SmartGate

### Authentication

SmartGate uses JWT authentication with team-based access:

```
POST {smartgate_host}/api/auth/token
{"team": "kukuvaia", "credentials": "..."}
→ {"jwt": "eyJ..."}
```

JWT is proactively refreshed before expiry (existing pattern from sl-content-engine).

### API

OpenAI-compatible:
```
POST {smartgate_host}/api/ai/chat/completions
Authorization: Bearer {jwt}
{"model": "eu.anthropic.claude-sonnet-4-5-20250929-v1:0", "messages": [...]}
```

### Models

Determined by SmartGate configuration. Currently: Claude Sonnet via AWS Bedrock.

---

## Commands

### `/login`

```
/login github       — start GitHub OAuth device flow
/login smartgate    — authenticate with SmartGate (JWT)
/login status       — show current auth state
/login logout       — clear stored credentials
```

### `/model`

```
/model              — show current model
/model list         — list available models for current provider
/model <name>       — switch model (e.g., /model claude-sonnet-4.5)
```

---

## CLI Flow

```
$ kukuvaia

No active session. Use /login to authenticate.

> /login github
Opening browser for GitHub authorization...
Enter the code: ABCD-1234 at https://github.com/login/device
Waiting for authorization... ✓
Authenticated as @bartek (Copilot Pro+)
Available models: 12 (use /model list)
Default model: claude-sonnet-4.5

> /model list
┏━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━┓
┃ Model                ┃ Provider ┃ Tier    ┃
┡━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━╇━━━━━━━━━┩
│ claude-sonnet-4.5    │ Copilot  │ Pro     │
│ claude-opus-4.5      │ Copilot  │ Pro+    │
│ gpt-4o               │ Copilot  │ Pro     │
│ gemini-2.5-pro       │ Copilot  │ Pro     │
│ ...                  │          │         │
└──────────────────────┴──────────┴─────────┘

> /model claude-opus-4.5
Model switched to claude-opus-4.5 (Copilot Pro+)
```

---

## Spring AI Integration

Both providers are OpenAI-compatible, so a single `OpenAiApi` / `OpenAiChatModel` abstraction works:

```java
@Component
public class LlmProviderService {

    private OpenAiChatModel activeChatModel;

    public void connectGitHubCopilot(String copilotToken, String model) {
        OpenAiApi api = new OpenAiApi("https://api.githubcopilot.com", copilotToken);
        this.activeChatModel = new OpenAiChatModel(api, OpenAiChatOptions.builder()
            .model(model)
            .temperature(0.2)
            .build());
    }

    public void connectSmartGate(String host, String jwt, String model) {
        OpenAiApi api = new OpenAiApi(host + "/api/ai", jwt);
        this.activeChatModel = new OpenAiChatModel(api, OpenAiChatOptions.builder()
            .model(model)
            .temperature(0.2)
            .build());
    }

    public OpenAiChatModel getActiveChatModel() {
        return activeChatModel;
    }
}
```

### Token Refresh

```java
@Component
public class CopilotTokenManager {

    private String githubOAuthToken;     // long-lived, stored
    private String copilotApiToken;      // 25-min TTL
    private Instant copilotTokenExpiry;

    public String getToken() {
        if (copilotTokenExpiry == null || Instant.now().isAfter(copilotTokenExpiry.minusSeconds(60))) {
            refreshCopilotToken();
        }
        return copilotApiToken;
    }

    private void refreshCopilotToken() {
        // GET api.github.com/copilot_internal/v2/token
        // Authorization: token {githubOAuthToken}
        // → new copilotApiToken + expiry (25 min)
    }
}
```

### ChatClient Re-binding on Provider Switch

```java
// When user runs /login or /model, rebuild ChatClient with new provider
public ChatClient rebuildChatClient(OpenAiChatModel chatModel, ChatMemory memory) {
    return ChatClient.builder(chatModel)
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(memory).build(),
            ToolCallAdvisor.builder().build()
        )
        .build();
}
```

---

## Token Storage

| Token | Lifespan | Storage |
|-------|----------|---------|
| GitHub OAuth token (`gho_xxx`) | Long-lived (until revoked) | `~/.kukuvaia/credentials.json` (file permissions 600) |
| Copilot API token | 25 minutes | In-memory only, auto-refreshed |
| SmartGate JWT | Hours (configurable) | In-memory, proactive refresh |

### Credentials File

```json
{
  "github": {
    "oauth_token": "gho_xxxxxxxxxxxx",
    "username": "bartek",
    "copilot_plan": "pro_plus"
  },
  "smartgate": {
    "host": "https://smartgate.example.com",
    "team": "kukuvaia"
  },
  "active_provider": "github",
  "active_model": "claude-sonnet-4.5"
}
```

File permissions: `chmod 600 ~/.kukuvaia/credentials.json`. Never committed to git.

---

## Architecture Decision: Provider Routing by Execution Context

### Decision

Interactive sessions (user-driven via CLI/Web) and daemon tasks (automated/scheduled) use **different LLM providers by default**. The provider is configurable at every level: global, per-daemon-task, per-specialist, per-request.

### Context

GitHub Copilot's API (`api.githubcopilot.com`) is an **undocumented internal API** intended for use through official clients (VS Code, JetBrains, GitHub CLI). Using it from automated daemon processes carries risks:

| Risk | Detail |
|------|--------|
| **Terms of Service** | GitHub Acceptable Use Policy prohibits "automated excessive bulk activity". The `copilot_internal` endpoint is not sanctioned for server-side use outside official clients. |
| **Abuse detection** | GitHub actively monitors for automated usage patterns. Users report receiving warnings: "Recent activity on your account has caught the attention of our abuse-detection systems." |
| **Quota exhaustion** | Premium request quotas (300-1,500/month depending on plan) burn fast with daemon workloads. A daemon running a few tasks per hour would exhaust monthly quota in days. |
| **API stability** | Undocumented endpoints can change without notice, breaking automated workflows. |

In contrast, **SmartGate** (corporate JWT-based, OpenAI-compatible) has no such restrictions — tokens refresh programmatically, no quota concerns beyond team-managed limits, no ToS gray area.

### Decision: Dual-Provider Routing

```
kukuvaia-server
  │
  ├── Interactive mode (user behind keyboard)
  │     Provider: user's choice via /login (Copilot or SmartGate)
  │     → Copilot OK — this is the intended use case
  │
  └── Daemon mode (automated, no user present)
        Provider: configurable, defaults to SmartGate
        → SmartGate safe — corporate JWT, no ToS issues
        → Copilot explicitly opt-in only (user accepts risk)
```

### Alternatives Considered

| Alternative | Verdict |
|---|---|
| **Use Copilot for everything** | Rejected — ToS risk for daemon, quota burns fast, abuse detection |
| **Use SmartGate for everything** | Rejected — Copilot offers wider model selection for interactive use, users want their subscription |
| **GitHub Models (pay-per-token)** | Viable for daemon — official API, no quota, but requires separate billing setup. Can be added as third provider later. |
| **Copilot SDK (official)** | Viable — explicitly supports server-side/CI/CD, uses PAT auth. But still counts against premium request quota. Can be evaluated as official daemon path when SDK matures. |

### Consequences

- `LlmProviderService` must support multiple concurrent providers (not just one active provider)
- Daemon tasks specify provider in config, falling back to global daemon default
- Sub-agents inherit provider from their execution context (interactive → user's provider, daemon → daemon's provider)
- Provider switching in daemon does not affect interactive sessions and vice versa

---

## Configurable Provider per Execution Context

### Provider Resolution Chain

Each LLM call resolves its provider through a fallback chain:

```
1. Explicit per-request override       (daemon task YAML: provider: smartgate)
2. Specialist definition               (specialists/analyst.yaml: provider: smartgate)
3. Execution context default            (daemon.default-provider: smartgate)
4. Global default                       (kukuvaia.default-provider: copilot)
```

### Configuration

**`application.yaml`**

```yaml
kukuvaia:
  default-provider: copilot              # global default (interactive sessions)
  
  daemon:
    enabled: true
    default-provider: smartgate           # daemon default — safe for automated use
    daily-token-budget: 100000            # cost guard
    
  providers:
    copilot:
      client-id: "Iv1.b507a08c87ecfe98"
      api-url: "https://api.githubcopilot.com"
      # Token from ~/.kukuvaia/credentials.json (user authenticates via /login)
    smartgate:
      host: "${SMARTGATE_HOST}"
      team: "${SMARTGATE_TEAM}"
      api-url: "${SMARTGATE_HOST}/api/ai"
      # JWT auto-refreshed
    github-models:                        # future: pay-per-token, official API
      api-url: "https://models.github.com"
      api-key: "${GITHUB_MODELS_TOKEN}"
```

**Daemon task YAML — per-task provider override**

```yaml
# .kukuvaia/daemon/nightly-review.yaml
name: nightly-review
specialist: analyst
schedule: "0 0 2 * * *"
provider: smartgate                       # explicit — this task uses SmartGate
```

```yaml
# .kukuvaia/daemon/pr-review.yaml
name: pr-review
specialist: reviewer
trigger: webhook
provider: copilot                         # explicit opt-in — user accepts quota/ToS risk
```

**Specialist YAML — per-specialist provider override**

```yaml
# specialists/analyst.yaml
name: analyst
provider: smartgate                       # analysts default to SmartGate
model: claude-sonnet
max_tokens: 4096
```

```yaml
# specialists/quick-check.yaml
name: quick-check
provider: copilot                         # quick checks use Copilot (cheaper model)
model: gpt-4.1                            # included model, no premium cost
max_tokens: 1024
```

### LlmProviderService — Multi-Provider Support

```java
@Component
public class LlmProviderService {

    private final Map<String, ChatModel> providers = new ConcurrentHashMap<>();
    private final ProviderConfig config;
    private final CopilotTokenManager copilotTokenManager;
    private final SmartGateAuthService smartGateAuth;

    @PostConstruct
    public void initProviders() {
        // SmartGate — always available (server-side JWT, no user interaction)
        if (config.smartgate().isConfigured()) {
            providers.put("smartgate", createSmartGateModel());
        }
        // Copilot — available after user /login (OAuth token from credentials file)
        if (copilotTokenManager.hasStoredCredentials()) {
            providers.put("copilot", createCopilotModel());
        }
        // GitHub Models — available if API key configured
        if (config.githubModels().isConfigured()) {
            providers.put("github-models", createGitHubModelsModel());
        }
    }

    /**
     * Resolve provider for a given execution context.
     * Follows fallback chain: explicit → context default → global default.
     */
    public ChatModel resolve(String explicitProvider, ExecutionContext context) {
        String providerName = explicitProvider;
        if (providerName == null) {
            providerName = switch (context) {
                case INTERACTIVE -> config.defaultProvider();          // copilot
                case DAEMON      -> config.daemon().defaultProvider(); // smartgate
            };
        }
        ChatModel model = providers.get(providerName);
        if (model == null) {
            throw new ProviderNotAvailableException(providerName,
                "Provider '%s' not configured or not authenticated. Run /login or check config."
                    .formatted(providerName));
        }
        return model;
    }

    /**
     * Register provider at runtime (e.g., after /login copilot).
     */
    public void registerProvider(String name, ChatModel model) {
        providers.put(name, model);
    }

    private OpenAiChatModel createSmartGateModel() {
        return new OpenAiChatModel(
            new OpenAiApi(config.smartgate().apiUrl(), smartGateAuth.getToken()),
            OpenAiChatOptions.builder()
                .model(config.smartgate().defaultModel())
                .temperature(0.2)
                .build());
    }

    private OpenAiChatModel createCopilotModel() {
        return new OpenAiChatModel(
            new OpenAiApi(config.copilot().apiUrl(), copilotTokenManager.getToken()),
            OpenAiChatOptions.builder()
                .model(config.copilot().defaultModel())
                .temperature(0.2)
                .build());
    }
}
```

### SubAgentFactory — Provider-Aware

```java
public String execute(String task, String specialistType, ExecutionContext context) {
    SubAgentSpec spec = specs.get(specialistType);
    
    // Resolve provider: specialist override → context default → global default
    ChatModel chatModel = providerService.resolve(spec.provider(), context);
    
    ChatClient subAgent = ChatClient.builder(chatModel)
        .defaultSystem(spec.systemPrompt())
        .defaultTools(spec.tools())
        .defaultAdvisors(new ToolCallAdvisor())
        .build();

    return subAgent.prompt()
        .options(OpenAiChatOptions.builder()
            .model(spec.model())
            .maxTokens(spec.maxTokens())
            .build())
        .user(task)
        .call().content();
}
```

### DaemonAgentService — Passes Daemon Context

```java
public DaemonTaskResult execute(String taskName, String specialistType, 
                                 String prompt, String triggerSource,
                                 String providerOverride) {
    // ...
    String result = subAgentFactory.execute(
        prompt, specialistType,
        ExecutionContext.DAEMON       // sub-agent resolves daemon default provider
    );
    // ...
}
```

### Provider Routing Summary

| Context | Default Provider | Override | Rationale |
|---------|-----------------|----------|-----------|
| Interactive session | `copilot` (user's subscription) | `/login` + `/model` | User-driven, intended Copilot use case |
| Daemon task | `smartgate` (corporate JWT) | `provider:` in daemon YAML | Safe for automated use, no ToS risk |
| Sub-agent (interactive) | Inherits from interactive | `provider:` in specialist YAML | Follows parent context |
| Sub-agent (daemon) | Inherits from daemon | `provider:` in specialist YAML | Follows parent context |
| Webhook-triggered | `smartgate` | `provider:` in webhook config | Automated, same as daemon |

---

## Security Considerations

- GitHub OAuth token grants `read:user` scope only — minimal permissions
- Copilot tokens are short-lived (25 min) — limits exposure window
- Credentials file is user-local, not in project directory
- No credentials in logs, system prompts, or error messages
- `/login logout` clears all stored tokens
- Server-side: credentials stored in memory per session, not persisted to database
- Daemon tasks default to SmartGate — no risk of burning user's Copilot quota without explicit opt-in
- Provider name logged with each daemon task execution for audit trail
