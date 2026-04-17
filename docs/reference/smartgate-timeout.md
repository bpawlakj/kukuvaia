# SmartGate — Upstream Timeout Limitation

## Issue

The SmartGate LiteLLM proxy enforces a hardcoded **10-second connection timeout** to upstream LLM providers. This is too short for tool-calling prompts, which typically need 30–60 seconds end-to-end.

## Workarounds that do not work

| Attempt | Why it fails |
|---------|--------------|
| `request_timeout` in JSON body | LiteLLM proxy ignores it — deployment info shows `request_timeout: None`. |
| `timeout` in JSON body | Also ignored. |
| `x-litellm-timeout` header | SmartGate returns **403 Forbidden** on unknown custom headers. |

The timeout is configured server-side in SmartGate's LiteLLM proxy config and cannot be overridden per request from the client.

## Practical implications

- SmartGate is only viable for **simple prompts that complete in under 10 seconds**. This excludes most tool-calling flows and multi-round agent conversations.
- For development and testing against kukuvaia's full agent loop, use a different provider:
  - **OpenRouter** — OpenAI-compatible, broad model catalog, no forced timeout.
  - **Direct provider APIs** — OpenAI, Anthropic, etc., with their own per-request timeout controls.
  - **Local runtime** — Ollama or LM Studio for offline development.

## Action

If production use on SmartGate becomes a requirement, the timeout limit must be raised in SmartGate's proxy configuration. Flag this to the SmartGate administrator with a concrete request budget (e.g., 60 s) and the tool-calling use case as justification.

## See also

- `smartgate-models.md` — catalog of models available via SmartGate.
