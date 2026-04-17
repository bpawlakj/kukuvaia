## API Design

### RESTful Principles
Use resource-based URLs with appropriate HTTP methods (GET, POST, PUT, PATCH, DELETE).

### Consistent Naming
Use lowercase, hyphenated or underscored names consistently across endpoints.

### Versioning
Implement versioning (URL path or headers) to manage breaking changes.

### Plural Nouns
Use plural nouns for resources (`/users`, `/products`).

### Limited Nesting
Keep URL nesting to 2-3 levels maximum for readability.

### Query Parameters
Use query parameters for filtering, sorting, and pagination.

### Proper Status Codes
Return appropriate HTTP status codes (200, 201, 400, 404, 500).

### Rate Limit Headers
Include rate limit information in response headers.

### Typed Tools via @McpTool
No raw SQL in tool implementations. Use `@McpTool` annotations with parameterized queries internally. The LLM never sees SQL -- only tool names and JSON schemas.

### Deterministic Slash Commands
Slash commands (`/validate`, `/check`, etc.) execute tools directly without involving the LLM. Free-form text goes through the `ChatClient` agent loop.

### SSE Streaming for Chat
`POST /api/chat` returns `Flux<OutputBlock>` as `TEXT_EVENT_STREAM`. Clients consume the SSE stream for real-time response rendering.

### Structured Output Blocks
Server responses use a sealed `OutputBlock` hierarchy: `TextBlock`, `TableBlock`, `CodeBlock`, `ProgressBlock`. Clients render each block type with appropriate formatting.

### REST API Conventions
All endpoints use `/api/` prefix with plural resource names. Controllers return `ResponseEntity` with `Map` for JSON responses.
