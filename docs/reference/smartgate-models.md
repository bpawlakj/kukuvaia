# SmartGate — Available Models

Reference catalog of LLM models confirmed working on the SmartGate proxy as of **2026-04-13**.

All models are accessed via `${LLM_BASE_URL}/chat/completions` with a Bearer JWT token. Model discovery was performed by probing SmartGate directly — the underlying LiteLLM proxy at `platform-acc-litellm.platform-acc.mbgaws.net` is not publicly reachable (VPC-only).

## Anthropic

| Alias | Model ID | Tier | Use case |
|-------|----------|------|----------|
| `sonnet` | `eu.anthropic.claude-sonnet-4-5-20250929-v1:0` | Premium | Main interactive chat |
| `opus` | `eu.anthropic.claude-opus-4-6-v1` | Premium+ | Complex reasoning, architecture decisions |
| `haiku` | `eu.anthropic.claude-haiku-4-5-20251001-v1:0` | Fast | Quick tasks, low-latency responses |

## OpenAI

| Alias | Model ID | Tier | Use case |
|-------|----------|------|----------|
| `gpt` | `gpt-4.1` | Premium | Alternative perspective, second opinion |

## Mistral

| Alias | Model ID | Tier | Use case |
|-------|----------|------|----------|
| `mistral` | `mistral.mistral-large-2402-v1:0` | Mid-premium | Coding, tool calling, EU provider. No `eu.` prefix — routed via US region. |

## Amazon Nova

| Alias | Model ID | Tier | Use case |
|-------|----------|------|----------|
| `nova-pro` | `eu.amazon.nova-pro-v1:0` | Mid | Balanced quality/cost |
| `nova-lite` | `eu.amazon.nova-lite-v1:0` | Budget | Daemon tasks, extraction |
| `nova-micro` | `eu.amazon.nova-micro-v1:0` | Cheapest | Classification, intent detection |

## Not available on SmartGate

Probes timed out (not configured in the proxy) for: Meta Llama 4 Scout/Maverick, Meta Llama 3.3/3.1, Mistral Large 2407/2411, Mistral Small, DeepSeek R1, Cohere Command R, AI21 Jamba, Amazon Titan Text, Amazon Nova Premier.

## Recommended routing for kukuvaia

- **Interactive chat**: `sonnet` (default), `opus` (complex), `haiku` (quick).
- **Daemon / extraction**: `nova-lite` (cheapest useful tier).
- **Intent detection**: `nova-micro` (fastest, cheapest).
- **Second opinion**: `gpt-4.1` or `mistral`.
- **Coding tasks**: `sonnet` or `mistral`.

## See also

- `smartgate-timeout.md` — SmartGate's 10-second upstream timeout limitation (blocks tool-calling flows).
