## Credentials

### Source of Secrets
Secrets (API keys, JWT tokens, DSN strings) are loaded from environment variables — in local development from `kukuvaia-engine/.env` (auto-loaded by `bootRun`), in production from the runtime's secret manager. There is no on-disk credentials file.

When OAuth flows are added later (e.g. GitHub Copilot device code), the implementer must reintroduce a file-permission guard (chmod 600, owner-only) before writing any token to disk.

### Never Hardcode Credentials
Secrets (JWT tokens, DSN strings, API keys) must come from environment variables or secure files only. Credentials must never appear in logs, LLM prompts, error messages, or source code.

```java
// Correct: from environment
@Value("${kukuvaia.provider.jwt:}")
private final String jwt;

// Wrong: hardcoded
private static final String JWT = "eyJhbG...";
```
