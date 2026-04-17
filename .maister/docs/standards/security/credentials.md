## Credentials

### Credentials chmod 600
`~/.kukuvaia/credentials.json` must have 600 permissions (owner read/write only). `CredentialsFileGuard` validates permissions at startup and refuses to proceed if the file is world-readable.

### Never Hardcode Credentials
Secrets (JWT tokens, DSN strings, API keys) must come from environment variables or secure files only. Credentials must never appear in logs, LLM prompts, error messages, or source code.

```java
// Correct: from environment
@Value("${kukuvaia.provider.jwt:}")
private final String jwt;

// Wrong: hardcoded
private static final String JWT = "eyJhbG...";
```
