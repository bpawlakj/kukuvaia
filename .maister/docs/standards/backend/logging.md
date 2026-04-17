## Logging

### SLF4J Logger Pattern
Every class that logs declares a private static final logger. The variable is always named `log`.

```java
private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);
```

### Structured Log Messages
Use parameterized `{}` format for log messages. No string concatenation. Include contextual identifiers (task IDs, session IDs, durations) for traceability.

```java
log.info("Provider resolved: {} for context: {} in {}ms", provider, context, duration);
log.warn("Budget threshold reached: {}% for daemon: {}", percentage, daemonName);
```

### Log Level Conventions
- **INFO** -- Normal operations, lifecycle events, provider resolution
- **WARN** -- Security events, budget thresholds, degraded operation
- **ERROR** -- Failures requiring attention, unrecoverable errors
- **DEBUG** -- Development-mode diagnostics, request/response details
