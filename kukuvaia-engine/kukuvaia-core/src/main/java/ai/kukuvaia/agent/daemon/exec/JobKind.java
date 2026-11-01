package ai.kukuvaia.agent.daemon.exec;

import java.util.Locale;

/** The kind of worker a daemon job runs on. Names match the YAML {@code kind:} values and the DB column. */
public enum JobKind {
    /** LLM specialist sub-agent (SubAgentFactory). */
    SPECIALIST(true),
    /** Deterministic Spring bean implementing {@code DaemonJob} — no direct LLM access. */
    JAVA(false),
    /** Embabel (GOAP) agent. */
    EMBABEL(true),
    /** A tool call on a remote MCP server (deterministic, no local LLM). */
    MCP(false),
    /** A remote agent over the A2A protocol. */
    A2A(false);

    private final boolean consumesLocalTokens;

    JobKind(boolean consumesLocalTokens) {
        this.consumesLocalTokens = consumesLocalTokens;
    }

    /** Whether runs of this kind spend LLM tokens on our account (and so are budget-gated). */
    public boolean consumesLocalTokens() {
        return consumesLocalTokens;
    }

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static JobKind fromString(String value) {
        if (value == null) throw new IllegalArgumentException("kind is required");
        return JobKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
