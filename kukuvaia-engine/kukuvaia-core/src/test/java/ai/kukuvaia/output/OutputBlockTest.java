package ai.kukuvaia.output;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputBlock — sealed hierarchy of typed output chunks")
class OutputBlockTest {

    @Test
    @DisplayName("TextBlock — holds content and style")
    void textBlock_creation_holdsValues() {
        var block = new TextBlock("Hello", "bold");
        assertThat(block.content()).isEqualTo("Hello");
        assertThat(block.style()).isEqualTo("bold");
        assertThat(block).isInstanceOf(OutputBlock.class);
    }

    @Test
    @DisplayName("TableBlock — holds title, headers, rows")
    void tableBlock_creation_holdsValues() {
        var block = new TableBlock("Results", List.of("Name", "Value"),
                List.of(List.of("a", "1"), List.of("b", "2")));
        assertThat(block.headers()).containsExactly("Name", "Value");
        assertThat(block.rows()).hasSize(2);
    }

    @Test
    @DisplayName("CodeBlock — holds content and language")
    void codeBlock_creation_holdsValues() {
        var block = new CodeBlock("System.out.println()", "java");
        assertThat(block.language()).isEqualTo("java");
    }

    @Test
    @DisplayName("ProgressBlock — holds label, current, total")
    void progressBlock_creation_holdsValues() {
        var block = new ProgressBlock("Validating", 3, 10);
        assertThat(block.current()).isEqualTo(3);
        assertThat(block.total()).isEqualTo(10);
    }

    @Test
    @DisplayName("sealed hierarchy — exhaustive pattern matching")
    void sealedHierarchy_switchExpression_exhaustive() {
        OutputBlock block = new TextBlock("test", null);
        String type = switch (block) {
            case TextBlock t -> "text";
            case TableBlock t -> "table";
            case CodeBlock c -> "code";
            case ProgressBlock p -> "progress";
            case PlanBlock p -> "plan";
            case PlanListBlock pl -> "plan_list";
            case VerificationBlock v -> "verification";
            case MetadataBlock m -> "metadata";
            case SpanEventBlock s -> "span_event";
            case ChoiceBlock c -> "choice";
        };
        assertThat(type).isEqualTo("text");
    }
}
