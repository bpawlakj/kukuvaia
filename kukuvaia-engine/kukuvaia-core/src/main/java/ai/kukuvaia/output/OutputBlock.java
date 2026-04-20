package ai.kukuvaia.output;

/**
 * Sealed hierarchy of typed output chunks.
 * Server sends these via SSE as JSON. CLI renders each type with dedicated Charm components.
 */
public sealed interface OutputBlock permits
        TextBlock, TableBlock, CodeBlock, ProgressBlock,
        PlanBlock, PlanListBlock, VerificationBlock, MetadataBlock,
        SpanEventBlock {
}
