package ai.kukuvaia.output;

import java.util.List;

public record TableBlock(String title, List<String> headers, List<List<String>> rows) implements OutputBlock {
}
