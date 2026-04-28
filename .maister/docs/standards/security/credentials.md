## Credentials

### Source of Secrets
Secrets (API keys, JWT tokens, DSN strings) can be provided in two ways, and `SecretResolver` disambiguates by format:

1. **Env-var reference** — when `providers.api_key_ref` matches the identifier pattern `^[A-Za-z_][A-Za-z0-9_]*$`, it is treated as the NAME of an environment variable and resolved via `System.getenv`. Unset or blank is a fail-fast error. In local development env vars come from `kukuvaia-engine/.env` (auto-loaded by `bootRun`); in production they come from the runtime's secret manager.
2. **Literal value** — when `providers.api_key_ref` contains characters that cannot appear in an env-var name (".", "-", "/", etc. — JWTs, OpenAI `sk-...` keys), the value is returned verbatim as the secret. This lets the admin UI persist a key directly without env-var plumbing.

Literal-value mode stores the key in plaintext in Postgres and is a stopgap. Before any shared or production deployment, keys held in the DB MUST be encrypted at rest (candidates: `pgcrypto` column encryption, Jasypt property-level encryption, or Vault transit). Rotate any key that was stored in plaintext when encryption lands.

There is no on-disk credentials file. When OAuth flows are added later (e.g. GitHub Copilot device code), the implementer must reintroduce a file-permission guard (chmod 600, owner-only) before writing any token to disk.

### Never Hardcode Credentials
Secrets (JWT tokens, DSN strings, API keys) must come from environment variables, the providers table, or secure files only. Credentials must never appear in logs, LLM prompts, error messages, or source code.

```java
// Correct: from environment
@Value("${kukuvaia.provider.jwt:}")
private final String jwt;

// Wrong: hardcoded
private static final String JWT = "eyJhbG...";
```
