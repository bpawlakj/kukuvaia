package ai.kukuvaia.output;

import java.util.Map;

public record MetadataBlock(Map<String, Object> metadata) implements OutputBlock {
}
