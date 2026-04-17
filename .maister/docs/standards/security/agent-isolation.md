## Agent Isolation

### Sub-Agent Isolation
Sub-agents use isolated `ChatClient` instances with no shared state. Maximum delegation depth is 1 (no sub-agent spawning sub-agents). `delegate_to_specialist` tool is filtered from sub-agent tool sets. Available tools are intersected with the persona's `tool_filter`.

### Daemon Budget Guard
Daily token budget with 80% alert threshold and hard stop at 100%. Prevents runaway daemon tasks from consuming excessive LLM resources.

### Skip-If-Running Cron
`DaemonScheduleGuard` prevents cron job overlap using `AtomicBoolean`. If a previous execution is still running, the new invocation is skipped and logged.

### Notification Summary Only
External notification sinks (Slack, email, webhooks) receive summary payloads only: task ID, name, status, duration. Full results are available only through the authenticated API.

### Provider Audit Logging
Every LLM call is logged with provider name, conversation context, specialist name, and token usage via `ProviderAuditLog` advisor. Enables cost tracking and anomaly detection.
