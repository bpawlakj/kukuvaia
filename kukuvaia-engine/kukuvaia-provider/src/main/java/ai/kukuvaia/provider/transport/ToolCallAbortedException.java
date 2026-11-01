package ai.kukuvaia.provider.transport;

/** A security or cancellation control signal; native transports must never turn it into tool data. */
public class ToolCallAbortedException extends IllegalStateException {
    public ToolCallAbortedException(String message) { super(message); }
}
