package ai.kukuvaia.output;

import java.util.List;

public record VerificationBlock(String path, boolean valid, List<String> issues, List<String> passed) implements OutputBlock {
}
