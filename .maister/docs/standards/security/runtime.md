## Runtime Security

### Never Run as Root
The agent process must not run as root. Shell commands executed by tools inherit OS-level permissions of the process owner.

### No Shell Expansion
Use `ProcessBuilder` for external commands (no shell interpretation). Disable `type: shell` in web API mode to prevent command injection.

```java
// Correct: ProcessBuilder, no shell
new ProcessBuilder("git", "log", "--oneline").start();

// Wrong: shell interpretation
Runtime.getRuntime().exec("sh -c " + userInput);
```

### Error Sanitization
`ErrorSanitizer` strips JDBC URLs, MongoDB URIs, JWTs, API tokens, file paths, and stack traces from error messages before they reach clients. Full error details are logged server-side only.

### Rate Limiting on Webhooks
Sliding window rate limiter per source IP, default 10 requests/minute. Returns HTTP 429 with `Retry-After` header when exceeded.
