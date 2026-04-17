package ai.kukuvaia.output;

public record ProgressBlock(String label, int current, int total) implements OutputBlock {
}
