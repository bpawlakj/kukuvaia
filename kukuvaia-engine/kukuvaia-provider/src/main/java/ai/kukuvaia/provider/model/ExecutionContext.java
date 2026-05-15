package ai.kukuvaia.provider.model;

/**
 * Execution context determines provider routing defaults.
 * Interactive = user's provider choice (Copilot default).
 * Daemon = server-side provider (SmartGate default).
 */
public enum ExecutionContext {
    INTERACTIVE,
    DAEMON
}
