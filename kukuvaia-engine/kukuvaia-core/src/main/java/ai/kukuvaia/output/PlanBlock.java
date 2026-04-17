package ai.kukuvaia.output;

import java.util.List;

public record PlanBlock(String task, List<String> steps, int completedCount) implements OutputBlock {
}
