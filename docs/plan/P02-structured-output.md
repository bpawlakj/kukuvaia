# P02: Structured Output -- JSON Schema Typed Responses from LLM

## Problem

LLM responses are currently free text wrapped in a `TextBlock(String content, String style)`. The CLI parses content heuristically to decide rendering (table? code? text?). There is no machine-readable type field in the SSE stream -- the CLI must guess. This leads to:

1. **Fragile parsing** -- CLI string-matches on markdown patterns to detect tables, code blocks, etc.
2. **No schema enforcement** -- LLM output format varies between models, prompts, and retries.
3. **Lost metadata** -- token usage, model info, and tool call details are not surfaced to the client.
4. **Command responses lack structure** -- slash commands that produce structured data (table of sessions, list of memories) serialize it as formatted text instead of typed JSON.

Spring AI 1.1 provides `BeanOutputConverter` which appends a JSON Schema directive to the prompt and parses the LLM response into a Java record. This gives us deterministic typed output without manual JSON parsing.

## Current State

### OutputBlock hierarchy

```
OutputBlock (sealed interface)
├── TextBlock(content, style)
├── TableBlock(title, headers, rows)
├── CodeBlock(language, content, filename)
├── ProgressBlock(label, current, total)
├── PlanBlock(title, steps)
├── VerificationBlock(status, checks)
└── MetadataBlock(key, value)
```

All OutputBlock subtypes are records with no `type` discriminator field. Jackson serialization relies on `@JsonTypeInfo` or the client knowing the concrete type. Currently, `AgentService.streamChat()` always returns `TextBlock`:

```java
// AgentService.java line 64
return Flux.just(new TextBlock(response, null));
```

### Slash commands return OutputBlocks

Commands like `/help`, `/model`, `/login` construct `List<OutputBlock>` directly. These are well-typed (some use `TableBlock`). The gap is in **LLM free-text responses** that should contain structured blocks.

### Spring AI structured output support

Spring AI 1.1 provides:
- `BeanOutputConverter<T>` -- generates JSON Schema from a Java class, appends format instructions to prompt, parses LLM response.
- `ChatClient.prompt().call().entity(Class<T>)` -- shorthand for the above.
- Works with records, lists, enums, nested types.
- Does NOT work with sealed interfaces (cannot auto-detect subtype from JSON).

### Key files

- `kukuvaia-core/src/main/java/ai/kukuvaia/output/OutputBlock.java` -- sealed interface
- `kukuvaia-core/src/main/java/ai/kukuvaia/output/TextBlock.java` -- `record TextBlock(String content, String style)`
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java` -- always returns TextBlock
- `kukuvaia-core/src/main/java/ai/kukuvaia/api/ChatController.java` -- SSE endpoint
- `kukuvaia-core/src/main/java/ai/kukuvaia/commands/SlashCommand.java` -- command interface
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/CommandRouter.java` -- routes slash vs free text

## Architecture

```
Request flow:
                                    Is it a command?
                                         |
                          +----- yes -----+------ no ------+
                          |                                 |
                    CommandRouter                     AgentService
                          |                                 |
                   SlashCommand.execute()          chatClient.prompt()
                          |                                 |
                   List<OutputBlock>                  free text response
                   (already typed)                          |
                          |                       StructuredOutputParser
                          |                                 |
                          |                   List<OutputBlock> or TextBlock
                          |                                 |
                          +------------ merge --------------+
                                         |
                              SSE stream (Flux<OutputBlock>)
                                         |
                              JSON with "type" discriminator
                                         |
                                    CLI renders

JSON wire format (after this plan):
{
  "type": "text",
  "content": "Here is the result...",
  "style": null
}
{
  "type": "table",
  "title": "Active Sessions",
  "headers": ["ID", "Created", "Messages"],
  "rows": [["sess-1", "2026-04-10", "12"]]
}
{
  "type": "code",
  "language": "java",
  "content": "public class Foo { }",
  "filename": "Foo.java"
}
```

### When to use structured vs free text

| Context | Output mode | Reason |
|---------|-------------|--------|
| Slash commands (`/help`, `/model`, `/sessions`) | Already structured -- `List<OutputBlock>` | Deterministic, no LLM involved |
| Tool results displayed to user | Structured (table/code) via post-processing | Tool output is data, not prose |
| Free-form chat (opinions, explanations, planning) | Free text wrapped in `TextBlock` | Forcing structure degrades conversation |
| Agent tasks with verifiable output (validation, analysis) | Structured via `BeanOutputConverter` | Need machine-readable results |
| Sub-agent results | Free text (summarized by parent) | Sub-agent output is intermediate |

Design principle: **structured output is opt-in per use case, not a global default.** Chat remains conversational. Only responses where the caller needs machine-readable structure use `BeanOutputConverter`.

## Implementation

### Step 1: Add Jackson `@JsonTypeInfo` to OutputBlock

This is the highest-value, lowest-risk change: every OutputBlock sent over SSE gets a `"type"` field so the CLI can switch on it without guessing.

**`kukuvaia-core/src/main/java/ai/kukuvaia/output/OutputBlock.java`:**

```java
package ai.kukuvaia.output;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed hierarchy of typed output chunks.
 * Server sends these via SSE as JSON. CLI renders each type with dedicated Charm components.
 * The "type" field is a Jackson discriminator included in every serialized JSON object.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.SubType(value = TextBlock.class, name = "text"),
        @JsonSubTypes.SubType(value = TableBlock.class, name = "table"),
        @JsonSubTypes.SubType(value = CodeBlock.class, name = "code"),
        @JsonSubTypes.SubType(value = ProgressBlock.class, name = "progress"),
        @JsonSubTypes.SubType(value = PlanBlock.class, name = "plan"),
        @JsonSubTypes.SubType(value = VerificationBlock.class, name = "verification"),
        @JsonSubTypes.SubType(value = MetadataBlock.class, name = "metadata")
})
public sealed interface OutputBlock permits
        TextBlock, TableBlock, CodeBlock, ProgressBlock,
        PlanBlock, VerificationBlock, MetadataBlock {
}
```

This change is backward-compatible: existing JSON output gains a `"type"` field, existing consumers that ignore unknown fields continue to work.

### Step 2: Create structured response records for agent tasks

Define typed response schemas for use cases where structured output adds value.

**`kukuvaia-core/src/main/java/ai/kukuvaia/output/structured/AnalysisResult.java`:**

```java
package ai.kukuvaia.output.structured;

import java.util.List;

/**
 * Structured output schema for analysis/validation agent tasks.
 * Used with Spring AI's BeanOutputConverter.
 */
public record AnalysisResult(
        String summary,
        List<Finding> findings,
        String recommendation
) {
    public record Finding(
            String category,
            String severity,
            String description,
            String location
    ) {}
}
```

**`kukuvaia-core/src/main/java/ai/kukuvaia/output/structured/ClassificationResult.java`:**

```java
package ai.kukuvaia.output.structured;

import java.util.List;

/**
 * Structured output for content classification tasks.
 */
public record ClassificationResult(
        List<ClassifiedItem> items,
        int totalProcessed,
        int totalClassified
) {
    public record ClassifiedItem(
            String id,
            String title,
            String classification,
            double confidence
    ) {}
}
```

### Step 3: Create StructuredOutputHelper utility

A thin wrapper around Spring AI's `BeanOutputConverter` that handles error fallback (if LLM returns malformed JSON, fall back to TextBlock).

**`kukuvaia-core/src/main/java/ai/kukuvaia/output/structured/StructuredOutputHelper.java`:**

```java
package ai.kukuvaia.output.structured;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Helper for extracting structured responses from LLM via Spring AI BeanOutputConverter.
 * Provides graceful fallback to TextBlock if the LLM response cannot be parsed.
 */
@Component
public class StructuredOutputHelper {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHelper.class);

    /**
     * Call the LLM with structured output expectations.
     * If the response parses successfully, applies the converter function to produce OutputBlocks.
     * If parsing fails, returns a TextBlock with the raw response.
     *
     * @param chatClient   the ChatClient to use
     * @param systemPrompt system prompt
     * @param userMessage  user message
     * @param sessionId    session ID for memory
     * @param responseType the expected response class
     * @param toBlocks     function to convert parsed response to OutputBlocks
     * @return list of OutputBlocks
     */
    public <T> java.util.List<OutputBlock> callStructured(
            ChatClient chatClient,
            String systemPrompt,
            String userMessage,
            String sessionId,
            Class<T> responseType,
            Function<T, java.util.List<OutputBlock>> toBlocks) {

        var converter = new BeanOutputConverter<>(responseType);

        try {
            String rawResponse = chatClient.prompt()
                    .system(systemPrompt + "\n\n" + converter.getFormat())
                    .user(userMessage)
                    .advisors(spec -> spec.param("chat_memory_conversation_id", sessionId))
                    .call()
                    .content();

            T parsed = converter.convert(rawResponse);
            if (parsed != null) {
                return toBlocks.apply(parsed);
            }
        } catch (Exception e) {
            log.warn("Structured output parsing failed, falling back to text: {}", e.getMessage());
        }

        // Fallback: return raw text
        String fallback = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(spec -> spec.param("chat_memory_conversation_id", sessionId))
                .call()
                .content();

        return java.util.List.of(new TextBlock(fallback, null));
    }
}
```

### Step 4: Integrate structured output into agent tasks

Modify `AgentService` to optionally use structured output for specific request types. Add a `format` field to `ChatRequest`.

**`kukuvaia-core/src/main/java/ai/kukuvaia/api/ChatRequest.java`:**

```java
package ai.kukuvaia.api;

/**
 * Inbound chat request.
 *
 * @param sessionId session identifier
 * @param message   user message or slash command
 * @param format    optional: "structured" to request typed blocks, null for default free text
 */
public record ChatRequest(String sessionId, String message, String format) {

    public ChatRequest(String sessionId, String message) {
        this(sessionId, message, null);
    }

    public boolean isStructured() {
        return "structured".equals(format);
    }
}
```

**`kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java` (add structured path):**

```java
/**
 * Stream a chat response with optional structured output.
 */
public Flux<OutputBlock> streamChat(String sessionId, String message, boolean structured) {
    if (!structured) {
        return streamChat(sessionId, message); // existing free-text path
    }

    // Structured path: use entity() for typed response
    try {
        PersonaSpec persona = personaService.getActivePersona(sessionId);

        // For structured requests, ask LLM for JSON
        String response = chatClient.prompt()
                .system(persona.systemPrompt() + STRUCTURED_SUFFIX)
                .user(message)
                .advisors(spec -> spec
                        .param("chat_memory_conversation_id", sessionId)
                        .param("kukuvaia.userId", USER_ID))
                .call()
                .content();

        // Parse JSON blocks from response
        List<OutputBlock> blocks = parseOutputBlocks(response);
        triggerExtraction(USER_ID, sessionId);
        return Flux.fromIterable(blocks);

    } catch (Exception e) {
        log.error("Structured chat failed: sessionId={}, error={}", sessionId, e.getMessage());
        return Flux.just(new TextBlock(sanitizeError(e), "error"));
    }
}

private static final String STRUCTURED_SUFFIX = """

        When your response contains structured data, format it as JSON blocks.
        Each block has a "type" field: "text", "table", "code", or "metadata".
        Return a JSON array of blocks. Example:
        [{"type":"text","content":"Analysis complete.","style":null},
         {"type":"table","title":"Results","headers":["Name","Value"],"rows":[["A","1"]]}]
        If your response is purely conversational, return a single text block.
        """;
```

### Step 5: Update ChatController to pass format

**`kukuvaia-core/src/main/java/ai/kukuvaia/api/ChatController.java`:**

```java
@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<OutputBlock> chat(@RequestBody ChatRequest request) {
    return commandRouter.route(request.message(), request.sessionId(), request.isStructured())
            .onErrorResume(e -> {
                log.error("SSE stream error: {}", e.getMessage());
                return Flux.just(new TextBlock("Error: " + e.getMessage(), "error"));
            });
}
```

### Step 6: Tool result formatting standards

Define a convention for how `@McpTool` methods format their return values so they render well as OutputBlocks.

**Convention (document in code, enforce in review):**

1. Tools returning tabular data should return JSON with explicit column structure:
   ```json
   {"columns": ["id", "name", "status"], "rows": [[1, "foo", "active"]]}
   ```
2. Tools returning a single value should return a plain string.
3. Tools returning errors should throw an exception (Spring AI handles tool errors).

This does not require code changes to existing tools -- it is a convention for new tools and gradual refactoring of existing ones.

## Configuration

No new configuration properties needed. The `@JsonTypeInfo` annotation works with the existing Jackson configuration. The `BeanOutputConverter` requires no configuration.

Optional: to control whether the default chat path uses structured output:

```yaml
kukuvaia:
  output:
    default-format: text  # "text" or "structured"
```

## Dependencies

No new Gradle dependencies. Everything needed is already available:

| Feature | Provided by |
|---------|------------|
| `@JsonTypeInfo`, `@JsonSubTypes` | `jackson-databind` (already in kukuvaia-core) |
| `BeanOutputConverter` | `spring-ai-starter-model-openai` (already in kukuvaia-core) |
| `ChatClient.prompt().call().entity()` | `spring-ai-client-chat` (already in kukuvaia-core) |

## Verification

### 1. JSON wire format includes type discriminator

```bash
# Start server
./gradlew :kukuvaia-app:bootRun &

# Send a chat message and inspect SSE events
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-struct-1","message":"Hello"}'

# Expected SSE event:
# data:{"type":"text","content":"Hello! How can I help...","style":null}
```

### 2. Jackson deserialization roundtrip test

```java
@Test
void outputBlock_serialization_includesTypeDiscriminator() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    OutputBlock block = new TextBlock("hello", null);
    String json = mapper.writeValueAsString(block);

    assertThat(json).contains("\"type\":\"text\"");
    assertThat(json).contains("\"content\":\"hello\"");

    OutputBlock deserialized = mapper.readValue(json, OutputBlock.class);
    assertThat(deserialized).isInstanceOf(TextBlock.class);
    assertThat(((TextBlock) deserialized).content()).isEqualTo("hello");
}

@Test
void tableBlock_serialization_includesTypeDiscriminator() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    OutputBlock block = new TableBlock("Test", List.of("A", "B"), List.of(List.of("1", "2")));
    String json = mapper.writeValueAsString(block);

    assertThat(json).contains("\"type\":\"table\"");

    OutputBlock deserialized = mapper.readValue(json, OutputBlock.class);
    assertThat(deserialized).isInstanceOf(TableBlock.class);
}
```

### 3. BeanOutputConverter integration test

```java
@Test
void analysisResult_converterGeneratesSchema() {
    var converter = new BeanOutputConverter<>(AnalysisResult.class);
    String format = converter.getFormat();

    assertThat(format).contains("summary");
    assertThat(format).contains("findings");
    assertThat(format).contains("severity");
}
```

### 4. Build passes

```bash
./gradlew clean build
```

## Effort Estimate

| Task | Effort | Notes |
|------|--------|-------|
| Add `@JsonTypeInfo` to OutputBlock (Step 1) | 0.5h | One annotation + update tests |
| Create structured response records (Step 2) | 0.5h | 2 new record files |
| Create StructuredOutputHelper (Step 3) | 1h | New file + unit test |
| Integrate into AgentService (Step 4) | 1.5h | Structured path + block parser |
| Update ChatRequest + ChatController (Step 5) | 0.5h | Add format field, pass through |
| Update OutputBlock tests | 0.5h | Serialization roundtrip tests |
| Document tool result conventions (Step 6) | 0.5h | Code comments / internal doc |
| **Total** | **~5h** | |

## Priority & Prerequisites

**Priority:** Medium-High -- the `@JsonTypeInfo` change (Step 1) is high-value and should ship early. Full structured output (Steps 2-5) can follow incrementally.

**Prerequisites:**
- None. All changes are additive.

**Sequencing:**
- Step 1 (`@JsonTypeInfo`) should be implemented immediately -- it is a one-line annotation that fixes the CLI's type-detection problem with zero risk.
- Steps 2-5 can be implemented as needed when specific agent tasks require structured output.
- The `StructuredOutputHelper` (Step 3) depends on validating `BeanOutputConverter` behavior with the SmartGate provider (some providers strip JSON schema formatting hints).

**Risks:**
- **Provider compatibility:** Not all LLM providers reliably follow JSON Schema instructions. SmartGate proxies Claude, which generally follows JSON instructions well, but this should be tested per model.
- **Token overhead:** `BeanOutputConverter.getFormat()` appends 200-500 tokens of schema instructions. Acceptable for infrequent structured calls, wasteful for every chat message -- hence the opt-in design.
- **Fallback reliability:** The `StructuredOutputHelper` makes a second LLM call on parse failure. This doubles latency and cost in error cases. An alternative is to return the raw text as a TextBlock without retry.
