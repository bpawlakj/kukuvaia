# Test Scenarios & Edge Cases

**Created**: 2026-04-13
**Covers**: All tasks T01-T15
**Focus**: Edge cases, security boundaries, failure modes, integration points

---

## T01: Provider Registry + Model CRUD

### Unit Tests: ProviderRepository

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 1.1 | Create provider | valid fields | saved, UUID generated | |
| 1.2 | Create duplicate name | same name twice | throws / unique violation | ✓ |
| 1.3 | Create with invalid type | type="unknown" | CHECK constraint violation | ✓ |
| 1.4 | Create with empty name | name="" | validation error | ✓ |
| 1.5 | Create with null base_url | baseUrl=null | NOT NULL violation | ✓ |
| 1.6 | Create with very long URL | 501 chars | VARCHAR(500) truncation or error | ✓ |
| 1.7 | Update provider | change base_url | updated, updated_at changes | |
| 1.8 | Update non-existent | random UUID | 0 rows affected / not found | ✓ |
| 1.9 | Delete provider | existing id | deleted, cascades to models | |
| 1.10 | Delete non-existent | random UUID | 0 rows affected / not found | ✓ |
| 1.11 | List empty | no providers in DB | empty list, not null | ✓ |
| 1.12 | Find by id | existing UUID | found | |
| 1.13 | Find by id missing | random UUID | Optional.empty | ✓ |
| 1.14 | JSONB config | nested JSON object | stored and retrieved correctly | |
| 1.15 | JSONB config null | config=null | defaults to '{}' | ✓ |
| 1.16 | Priority ordering | 3 providers with priority 0,5,10 | list ordered by priority DESC | |
| 1.17 | Special chars in name | name="smart-gate_v2" | accepted (alphanumeric + special) | ✓ |
| 1.18 | Unicode in name | name="провайдер" | accepted if VARCHAR allows | ✓ |
| 1.19 | SQL injection in name | name="'; DROP TABLE providers; --" | parameterized query prevents | ✓ |
| 1.20 | Concurrent create same name | parallel inserts | one succeeds, other gets unique violation | ✓ |

### Unit Tests: ModelRepository

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 1.21 | Create model | valid fields + provider FK | saved | |
| 1.22 | Create with non-existent provider | random provider_id | FK violation | ✓ |
| 1.23 | Create duplicate model_id per provider | same (provider_id, model_id) | unique constraint violation | ✓ |
| 1.24 | Same model_id different providers | "haiku" for provider A and B | both accepted (different provider FK) | ✓ |
| 1.25 | Create with invalid tier | tier="mega" | CHECK constraint violation | ✓ |
| 1.26 | JSONB capabilities array | ["text","code","vision"] | stored, queryable with @> | |
| 1.27 | Empty capabilities | capabilities=[] | defaults to '[]' | ✓ |
| 1.28 | Capabilities GIN search | find models with "vision" | only vision-capable returned | |
| 1.29 | Filter by tier | tier="economy" | only economy models | |
| 1.30 | Filter enabled only | enabled=true | disabled excluded | |
| 1.31 | Delete provider cascades | delete provider with 3 models | all 3 models deleted | |
| 1.32 | max_tokens zero | max_tokens=0 | default 4096 or validation error | ✓ |
| 1.33 | max_tokens negative | max_tokens=-1 | validation error | ✓ |
| 1.34 | context_window null | null | accepted (nullable column) | ✓ |
| 1.35 | discovered_at set | auto-discovery timestamp | stored, distinguishes manual vs discovered | |

### Unit Tests: ModelRoleRepository

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 1.36 | Assign role | role="default", model_id=uuid | saved | |
| 1.37 | Assign duplicate role | same role twice | unique violation (one model per role) | ✓ |
| 1.38 | Upsert role | change model for existing role | updated, not duplicated | |
| 1.39 | Delete role | remove "worker" | deleted | |
| 1.40 | Delete model with active role | model is "advisor" | RESTRICT — deletion blocked | ✓ |
| 1.41 | Assign role to disabled model | model.enabled=false | allowed (DB doesn't enforce) — validate in service | ✓ |
| 1.42 | Assign role to non-existent model | random UUID | FK violation | ✓ |
| 1.43 | List all roles | 3 roles assigned | all returned with model details | |
| 1.44 | Find by role name | "advisor" | returns model_id + details | |
| 1.45 | Find non-existent role | "nonexistent" | Optional.empty | ✓ |

### API Tests: ProviderController

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 1.46 | POST valid provider | complete JSON | 201 Created, body without apiKeyRef | |
| 1.47 | POST missing required field | no baseUrl | 400 Bad Request | ✓ |
| 1.48 | POST invalid JSON | malformed body | 400 Bad Request | ✓ |
| 1.49 | POST empty body | {} | 400 validation errors | ✓ |
| 1.50 | GET providers empty DB | no data | 200, empty array | ✓ |
| 1.51 | GET provider by ID | existing | 200, apiKeyRef NOT in response | |
| 1.52 | GET provider 404 | random UUID | 404 Not Found | ✓ |
| 1.53 | GET provider invalid UUID | "not-a-uuid" | 400 Bad Request | ✓ |
| 1.54 | PUT update provider | change name | 200, updated | |
| 1.55 | DELETE provider with models | has 3 models | 200, models cascaded | |
| 1.56 | DELETE provider with role-assigned model | model is "advisor" | 409 Conflict — cannot cascade past RESTRICT | ✓ |
| 1.57 | Response never contains apiKeyRef | any GET | apiKeyRef field absent from JSON | ✓ |

### API Tests: ModelController

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 1.58 | POST roles bulk assign | 3 assignments | 200, all assigned | |
| 1.59 | POST roles with invalid model_id | non-existent UUID | 404 for that model | ✓ |
| 1.60 | POST roles reassign existing | change "advisor" to different model | updated, old model freed | |
| 1.61 | DELETE role | remove "worker" | 200, role removed | |
| 1.62 | DELETE non-existent role | "fantasy" | 404 | ✓ |
| 1.63 | GET models filter by tier | tier=economy | only economy tier returned | |
| 1.64 | GET models filter by capability | capability=vision | GIN index query works | |

---

## T02: ChatModel Factory + Cache

### Unit Tests: SecretResolver

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 2.1 | Resolve existing env var | "HOME" | /home/... value | |
| 2.2 | Resolve non-existent env var | "NONEXISTENT_KEY_XYZ" | SecretNotFoundException | ✓ |
| 2.3 | Resolve blank env var | env var set to "" | SecretNotFoundException | ✓ |
| 2.4 | Resolve null reference | null | NullPointerException or IllegalArgument | ✓ |
| 2.5 | Resolve with whitespace | " SMARTGATE_KEY " | trimmed lookup or not found | ✓ |

### Unit Tests: ChatModelFactory

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 2.6 | Create from valid records | provider + model | OpenAiChatModel with correct base_url and model | |
| 2.7 | Create with unresolvable key | api_key_ref="MISSING" | SecretNotFoundException | ✓ |
| 2.8 | Create with custom temperature | config: {"temperature": 0.8} | ChatOptions.temperature=0.8 | |
| 2.9 | Create with no config | config={} | default temperature (0.2) | ✓ |
| 2.10 | Create with max_tokens from model | max_tokens=8192 | ChatOptions.maxTokens=8192 | |
| 2.11 | Base URL with trailing slash | "https://api.example.com/" | works (no double slash) | ✓ |
| 2.12 | Base URL without trailing slash | "https://api.example.com" | works | |
| 2.13 | Base URL with path | "https://proxy.com/v1" | preserved correctly | ✓ |
| 2.14 | Model ID with special chars | "eu.anthropic.claude-haiku-4-5-20251001-v1:0" | passed correctly to OpenAI API | ✓ |

### Unit Tests: ChatModelCache

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 2.15 | Get by model ID (cold) | UUID not in cache | creates via factory, caches, returns | |
| 2.16 | Get by model ID (warm) | UUID already cached | returns cached, no factory call | |
| 2.17 | Get by role | "default" | resolves role → UUID → ChatModel | |
| 2.18 | Get by non-existent role | "fantasy" | throws or returns null | ✓ |
| 2.19 | Get by role when no roles assigned | empty model_roles | throws ProviderNotAvailableException | ✓ |
| 2.20 | Invalidate model | existing UUID | removed from cache, next access recreates | |
| 2.21 | Invalidate non-cached model | UUID not in cache | no-op, no error | ✓ |
| 2.22 | Invalidate provider | provider with 3 models | all 3 evicted | |
| 2.23 | Refresh roles | role changed in DB | roleIndex updated, old mapping gone | |
| 2.24 | Concurrent getByModelId | 10 threads, same UUID | only 1 factory call (computeIfAbsent) | ✓ |
| 2.25 | Concurrent getByRole | 10 threads, same role | consistent result, no race | ✓ |
| 2.26 | WarmUp with no roles | empty model_roles table | no ChatModels pre-created, no error | ✓ |
| 2.27 | WarmUp with unresolvable key | role points to model with bad key | logs error, skips that model, continues | ✓ |
| 2.28 | Model deleted while cached | model removed from DB, still in cache | next invalidation clears it. Direct access returns stale. | ✓ |

### Unit Tests: ProviderSeeder

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 2.29 | Seed empty DB with env vars | LLM_BASE_URL + LLM_API_KEY + LLM_MODEL set | provider + model + "default" role created | |
| 2.30 | Seed empty DB without env vars | no LLM_ env vars | no seeding, no error | ✓ |
| 2.31 | Seed non-empty DB | providers table has data | skip seeding (don't overwrite) | ✓ |
| 2.32 | Seed with partial env vars | LLM_BASE_URL set but no LLM_API_KEY | skip seeding, log warning | ✓ |
| 2.33 | Seed idempotent | run twice | second run is no-op | ✓ |
| 2.34 | LLM_MODEL not set | only BASE_URL + API_KEY | seed with default model name "claude-sonnet-4.5" | ✓ |

### Integration Tests: LlmProviderService Refactor

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 2.35 | resolve("default", INTERACTIVE) | default role assigned | returns ChatModel | |
| 2.36 | resolve("advisor", DAEMON) | advisor role assigned | returns Opus ChatModel | |
| 2.37 | resolve("nonexistent", any) | no such role or provider | ProviderNotAvailableException | ✓ |
| 2.38 | resolve(null, INTERACTIVE) | null explicit | falls back to "default" role | ✓ |
| 2.39 | resolve("", INTERACTIVE) | empty string | falls back to "default" role | ✓ |
| 2.40 | resolveByRole("worker") | worker role assigned | returns Haiku ChatModel | |
| 2.41 | Backward compat: register() | manual registration | still works alongside DB | ✓ |
| 2.42 | DB change + invalidation | update provider base_url via API | next resolve returns new ChatModel | |

---

## T03: Model Discovery

### Unit Tests: ModelDiscoveryClient

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 3.1 | Discover from valid response | OpenAI format JSON | list of DiscoveredModel | |
| 3.2 | Provider returns 401 | invalid API key | clear error: "API key invalid" | ✓ |
| 3.3 | Provider returns 404 | /v1/models not supported | clear error: "endpoint not supported" | ✓ |
| 3.4 | Provider timeout | no response in 10s | timeout error, not hang | ✓ |
| 3.5 | Provider returns empty list | {"data": []} | empty list, no error | ✓ |
| 3.6 | Provider returns unexpected JSON | missing "data" field | parse error handled gracefully | ✓ |
| 3.7 | Provider returns HTML (wrong URL) | HTML page | parse error, not crash | ✓ |
| 3.8 | Model ID with special chars | "eu.anthropic.claude-haiku:v1" | preserved correctly | ✓ |
| 3.9 | Duplicate model IDs in response | same model_id twice | deduplicated | ✓ |
| 3.10 | Very large response | 1000 models | all parsed, no OOM | ✓ |

### Integration Tests: Sync

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 3.11 | First sync | 4 models discovered | 4 added, 0 removed | |
| 3.12 | Re-sync no changes | same 4 models | 0 added, 0 removed, 4 unchanged | |
| 3.13 | Re-sync new model added | 5th model on provider | 1 added, 0 removed | |
| 3.14 | Re-sync model disappeared | 1 model gone from provider | 0 added, 1 soft-disabled | |
| 3.15 | Sync provider with connection error | provider down | error response, existing models untouched | ✓ |

---

## T04: Embabel Activation

### Boot Tests

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 4.1 | Boot without AgentPlatform exclusion | app starts, no bean conflicts | |
| 4.2 | Boot with no providers in DB | Embabel boots, ModelProvider has no models | ✓ |
| 4.3 | Boot with providers but no roles | Embabel boots, role lookup returns null | ✓ |
| 4.4 | PingAgent still works | deterministic test passes | |
| 4.5 | Embabel AgentPlatform bean in context | injectable, not null | |
| 4.6 | @Primary ModelProvider overrides auto-config | our bean used, not Embabel's default | ✓ |
| 4.7 | ModelProvider with cheapest role | getLlm("cheapest") → Haiku | |
| 4.8 | ModelProvider with best role | getLlm("best") → Opus | |
| 4.9 | ModelProvider with undefined role | getLlm("fantasy") → error or fallback | ✓ |
| 4.10 | Role changed in DB at runtime | refreshRoles() → new model for role | |

---

## T05: Model Routing Advisor

### Unit Tests: ModelRoutingAdvisor

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 5.1 | Short greeting | "hi" | routes to worker (Haiku) | |
| 5.2 | Short greeting variant | "hello!" | routes to worker | |
| 5.3 | Simple question | "what is 2+2?" | routes to worker | |
| 5.4 | Normal task | "explain this function" | routes to default (Sonnet) | |
| 5.5 | Analysis request | "analyze the performance of this query" | routes to default | |
| 5.6 | Complex request | "design a microservice architecture for..." | routes to default or advisor | |
| 5.7 | Explicit escalation phrase | "think harder about this" | routes to advisor (Opus) | ✓ |
| 5.8 | Loop flag set | kukuvaia.escalate=true in context | routes to advisor | |
| 5.9 | Empty message | "" | routes to default (safe fallback) | ✓ |
| 5.10 | Very long message | 10000 chars | classified without timeout | ✓ |
| 5.11 | Non-English message | Polish text | classification still works | ✓ |
| 5.12 | Mixed signals | "hi, analyze this complex architecture" | higher complexity wins | ✓ |
| 5.13 | Only code, no text | "```java\npublic class Foo {}```" | routes to default | ✓ |
| 5.14 | No roles configured in DB | empty model_roles | fallback to auto-configured model | ✓ |

### Unit Tests: LoopDetectionAdvisor

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 5.15 | Normal response (no tools) | response without tool calls | no loop state change | |
| 5.16 | 1 tool round | response with tool call | counter=1, no action | |
| 5.17 | 5 tool rounds (warning) | 5 consecutive tool responses | warning hint injected | |
| 5.18 | 8 tool rounds (escalation) | 8 consecutive | escalation flag set | |
| 5.19 | 15 tool rounds (abort) | 15 consecutive | error returned, state reset | |
| 5.20 | Successful response after tools | tool rounds → then no-tool response | state reset to 0 | |
| 5.21 | Different sessions | session A at 5, session B at 0 | independent counters | |
| 5.22 | Session state cleanup | old session not used for 1 hour | eventually cleaned from map | ✓ |
| 5.23 | Concurrent requests same session | parallel tool responses | thread-safe counter | ✓ |
| 5.24 | Custom thresholds from config | warning=3, escalation=5 | respects config values | |

---

## T06: Data Masking

### Unit Tests: MaskingService

| # | Scenario | Input | Expected | Edge Case? |
|---|----------|-------|----------|:----------:|
| 6.1 | PESEL detection | "PESEL: 89012345678" | "PESEL: [ID_1]" | |
| 6.2 | Email detection | "send to jan@example.com" | "send to [EMAIL_1]" | |
| 6.3 | Phone detection | "+48 123 456 789" | "[PHONE_1]" | |
| 6.4 | Credit card | "4111-1111-1111-1111" | "[CC_1]" | |
| 6.5 | API key pattern | "sk-1234567890abcdefghij" | "[SECRET_1]" | |
| 6.6 | IBAN | "PL61109010140000071219812874" | "[IBAN_1]" | |
| 6.7 | Multiple PII in one message | name + email + phone | all masked with incrementing IDs | |
| 6.8 | No PII | "how to write a Java loop?" | unchanged, empty mapping | |
| 6.9 | PII-like but not PII | "11 digit number: 12345678901" | may mask (false positive — acceptable) | ✓ |
| 6.10 | Already masked text | "[ID_1] already present" | not double-masked | ✓ |
| 6.11 | Empty string | "" | empty, no error | ✓ |
| 6.12 | Very long text | 50000 chars with 100 emails | all masked, performance <10ms | ✓ |
| 6.13 | Overlapping patterns | phone embedded in credit card | correctly resolved (longer match wins) | ✓ |
| 6.14 | URL with email-like part | "https://user@host.com/path" | may mask email part (acceptable) | ✓ |
| 6.15 | Code with hardcoded IP | "bind to 192.168.1.1:8080" | IP masked if enabled | |
| 6.16 | Token unmapping correct | "[EMAIL_1]" in response → original email | exact reverse mapping | |
| 6.17 | Token not in response | LLM doesn't use "[ID_1]" | unmapping is no-op for unused tokens | ✓ |
| 6.18 | LLM generates new brackets | "[SOME_OTHER]" in response | left as-is (not in mapping) | ✓ |

---

## T07: SubAgentFactory Tiers

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 7.1 | Spec with tier="worker" | resolves to Haiku via DB | |
| 7.2 | Spec with tier=null, model="sonnet" | uses model name directly (backward compat) | ✓ |
| 7.3 | Spec with tier=null, model=null | falls back to default role | ✓ |
| 7.4 | Spec with tier="nonexistent" | falls back to model or default | ✓ |
| 7.5 | Tier role not in DB | role "worker" not assigned | falls back to model field | ✓ |
| 7.6 | Tier + model both set | tier takes priority | ✓ |
| 7.7 | YAML without tier field | parsed as null, backward compat | ✓ |

---

## T08: Embabel Agent PoC

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 8.1 | ResearchAgent GOAP plan | discovers quickScan → deepAnalysis sequence | |
| 8.2 | ResearchAgent model per action | quickScan=cheapest, deepAnalysis=best | |
| 8.3 | ValidationAgent GOAP plan | discovers correct action sequence | |
| 8.4 | Agent with no memories | MemoryRetrievalService returns empty | works without context | ✓ |
| 8.5 | Agent with memory injection | relevant memories returned | injected into action context | |
| 8.6 | Agent action throws exception | LLM call fails | GOAP handles error, doesn't crash | ✓ |
| 8.7 | Parallel actions possible | GOAP identifies independent steps | executed concurrently | |

---

## T09: Task Decomposition

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 9.1 | @ActionComplexity(EXTRACTION) | resolves to worker/Haiku | |
| 9.2 | @ActionComplexity(STRATEGY) | resolves to advisor/Opus | |
| 9.3 | No @ActionComplexity annotation | fallback to default/Sonnet | ✓ |
| 9.4 | Complexity type not in DB mappings | fallback to default | ✓ |
| 9.5 | Mapping changed at runtime | next execution uses new model | |
| 9.6 | All mappings point to same model | works (degenerate case) | ✓ |

---

## T10: Daemon File/Git Tools

### Security Edge Cases (Critical)

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 10.1 | readFile within workspace | returns content | |
| 10.2 | readFile outside workspace | **BLOCKED** — WorkspaceSandbox rejects | ✓ |
| 10.3 | readFile with ../ traversal | "workspace/../../../etc/passwd" → **BLOCKED** | ✓ |
| 10.4 | readFile via symlink escape | symlink in workspace points to /etc | **BLOCKED** — resolve real path first | ✓ |
| 10.5 | writeFile within workspace | file created/updated | |
| 10.6 | writeFile outside workspace | **BLOCKED** | ✓ |
| 10.7 | writeFile to /etc/passwd | **BLOCKED** | ✓ |
| 10.8 | editFile matching text | old text replaced with new | |
| 10.9 | editFile non-matching text | error: old text not found | ✓ |
| 10.10 | listFiles with glob | returns matching paths within workspace only | |
| 10.11 | listFiles outside workspace | **BLOCKED** or returns empty | ✓ |
| 10.12 | bashRun allowed command | "git status" → output | |
| 10.13 | bashRun blocked command | "rm -rf /" → **BLOCKED** by allowlist | ✓ |
| 10.14 | bashRun command injection | "git status; rm -rf /" → **BLOCKED** (ProcessBuilder, no shell) | ✓ |
| 10.15 | bashRun pipe | "git log \| head" → **BLOCKED** (no shell expansion) | ✓ |
| 10.16 | bashRun backtick | "git \`rm -rf /\` status" → **BLOCKED** | ✓ |
| 10.17 | gitCommit | valid message | commit created locally | |
| 10.18 | gitPush attempt | tool not available | no push tool exists | ✓ |
| 10.19 | gitReset attempt | not in allowlist | **BLOCKED** | ✓ |
| 10.20 | Large file read | 100MB file | size limit enforced or streaming | ✓ |
| 10.21 | Binary file read | .jar, .png | return error or base64 | ✓ |
| 10.22 | Concurrent file writes | two daemon tasks write same file | one wins, no corruption | ✓ |
| 10.23 | Empty workspace | no files | listFiles returns empty, readFile errors | ✓ |

---

## T11: Harness Engineering

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 11.1 | Platform rules apply to all users | rule injected for every request | |
| 11.2 | Group rule applies to members | member sees rule, non-member doesn't | |
| 11.3 | User rule overrides group rule | same key, user level wins | ✓ |
| 11.4 | Session rule overrides all | temporary rule wins over user+group | ✓ |
| 11.5 | User in multiple groups | rules from all groups merged | ✓ |
| 11.6 | Conflicting rules from two groups | higher priority group wins | ✓ |
| 11.7 | Activation by file pattern | *.java rule active when editing Java | |
| 11.8 | Activation by intent | ANALYSIS rule active for analysis intent | |
| 11.9 | No matching activation | rule not injected (correctly filtered) | ✓ |
| 11.10 | Disabled rule | enabled=false | not injected even if activation matches | ✓ |
| 11.11 | Disabled rule set | entire set disabled | none of its rules injected | ✓ |
| 11.12 | Empty rules | user has no rules | system prompt has no harness section | ✓ |
| 11.13 | Very many rules | 100 rules resolved | token budget limits total size | ✓ |
| 11.14 | Cache invalidation | update rule via API | next request uses new rule (after TTL or immediate) | |
| 11.15 | Import CLAUDE.md format | paste existing CLAUDE.md | parsed into rule set correctly | |
| 11.16 | Circular group hierarchy | group A parent of B parent of A | detected and rejected | ✓ |
| 11.17 | User removed from group | was member, now removed | rules no longer apply | |

---

## T12: Dreaming Agent

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 12.1 | Health check all providers healthy | all green in report | |
| 12.2 | Health check provider down | degraded status reported | ✓ |
| 12.3 | Health check provider key expired | auth failure detected | ✓ |
| 12.4 | Config audit orphaned role | role points to disabled model | warning in report | ✓ |
| 12.5 | Config audit missing env var | api_key_ref env var not set | warning in report | ✓ |
| 12.6 | Memory consolidation | 50 stale memories found | recommendation to consolidate | |
| 12.7 | Memory contradictions | two memories contradict each other | detected, listed in report | ✓ |
| 12.8 | Dream during daemon budget exhaustion | budget at 95% | dream skipped or limited | ✓ |
| 12.9 | Dream overlapping run | previous run still active | skip-if-running guard | ✓ |
| 12.10 | Manual trigger | POST /api/dream/trigger | immediate execution | |
| 12.11 | Accept recommendation | POST accept | action applied | |
| 12.12 | Reject recommendation | POST reject with reason | recorded, not applied | |
| 12.13 | Recommendation expires | older than 7 days, not acted on | status → expired | ✓ |
| 12.14 | No anomalies found | everything healthy | "no issues" report (not empty) | ✓ |

---

## T13: Inter-Agent Communication

| # | Scenario | Expected | Edge Case? |
|---|----------|----------|:----------:|
| 13.1 | Claude Code connects via MCP | getTaskContext returns data | |
| 13.2 | Invalid task-scoped token | expired or wrong task ID | 401 Unauthorized | ✓ |
| 13.3 | Token for different task | valid token, wrong taskId | 403 Forbidden | ✓ |
| 13.4 | reportProgress(101) | progress > 100 | clamped to 100 or validation error | ✓ |
| 13.5 | reportProgress(-1) | negative | clamped to 0 or validation error | ✓ |
| 13.6 | submitResult twice | task already completed | second call rejected or idempotent | ✓ |
| 13.7 | requestHelp | stuck on problem | Opus advisor called, guidance returned | |
| 13.8 | requestHelp when Opus unavailable | no advisor role assigned | fallback to Sonnet, or error | ✓ |
| 13.9 | Agent disconnects mid-task | MCP connection lost | task timeout → status FAILED | ✓ |
| 13.10 | Very large artifact | 10MB code diff | size limit enforced | ✓ |
| 13.11 | Concurrent MCP calls | 5 parallel tool calls | all processed, no data race | ✓ |
| 13.12 | Agent registered but disabled | enabled=false | task creation rejected | ✓ |

---

## Cross-Task Integration Tests

### End-to-End: Provider → Model → Route → Chat

| # | Scenario | Tasks | Steps |
|---|----------|-------|-------|
| E2E-1 | Full setup + chat | T01→T02→T05 | Register SmartGate → sync models → assign roles → send "hi" → routed to Haiku |
| E2E-2 | Loop escalation | T05 | Send message → trigger tool loop → warning at 5 → escalation at 8 → verify Opus used |
| E2E-3 | PII masked chat | T01→T02→T05→T06 | Configure masking → send message with PESEL → verify LLM received [ID_1] → response unmasked |
| E2E-4 | Embabel research with routing | T01→T02→T04→T08 | Assign roles → invoke ResearchAgent → verify Haiku for scan, Opus for analysis |
| E2E-5 | Daemon coding task | T01→T02→T07→T10 | Assign roles → daemon with "worker" tier → readFile + editFile → verify sandbox |
| E2E-6 | Dream health check | T01→T02→T03→T12 | Register providers → trigger dream → health report shows all providers |
| E2E-7 | Harness + routing | T02→T05→T11 | Create group rules → send message → verify rules in system prompt AND correct model |
| E2E-8 | External agent via MCP | T01→T02→T11→T13 | Register agent → create task → agent calls getTaskContext → reportProgress → submitResult |
| E2E-9 | Full flow | All | Register provider → sync → assign roles → set harness rules → chat with routing → daemon fix → dream report |

### Backward Compatibility

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| BC-1 | Boot with only env vars | LLM_BASE_URL + LLM_API_KEY set, empty DB | ProviderSeeder creates default, chat works |
| BC-2 | Existing sessions work | sessions from before migration | session history still accessible |
| BC-3 | Existing memories work | memories from before migration | semantic search still works |
| BC-4 | Existing tools work | MemoryTools, PlanningTools | tool calling unchanged |
| BC-5 | Existing tests pass | full test suite | 171+ tests green |
| BC-6 | SubAgentSpec without tier | YAML without tier field | parsed as null, backward compat |

### Performance

| # | Scenario | Threshold |
|---|----------|-----------|
| P-1 | Provider CRUD response time | < 50ms |
| P-2 | ChatModelCache.getByRole() | < 1ms (cached) |
| P-3 | ModelRoutingAdvisor.before() | < 5ms |
| P-4 | DataMaskingAdvisor.before() | < 5ms (8 regex patterns) |
| P-5 | HarnessAdvisor.before() (cached) | < 5ms |
| P-6 | HarnessAdvisor.before() (cold) | < 20ms (DB query) |
| P-7 | Full advisor chain overhead | < 30ms total (vs 2-30s LLM call) |
