package ai.kukuvaia.agent.daemon.exec;

/**
 * A run that failed after spending a <em>known</em> number of tokens (e.g. an Embabel agent stopped by
 * its token budget). The runtime charges the reported usage instead of the conservative
 * {@code token_budget}; if usage is not reported it still charges the conservative amount.
 */
public class ExecutionFailedException extends RuntimeException {

    private final int promptTokens;
    private final int completionTokens;
    private final boolean usageReported;
    private final boolean timedOut;

    public ExecutionFailedException(String message, int promptTokens, int completionTokens,
                                    boolean usageReported, boolean timedOut) {
        super(message);
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.usageReported = usageReported;
        this.timedOut = timedOut;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean usageReported() {
        return usageReported;
    }

    /** The run hit its deadline (audit status TIMEOUT rather than FAILED). */
    public boolean timedOut() {
        return timedOut;
    }
}
