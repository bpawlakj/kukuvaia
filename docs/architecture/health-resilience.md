# Health Check & Resilience — Agent ↔ Gateway

## Problem

Agent depends on MCP Gateway for all tool execution. Three failure scenarios:

1. **Gateway down at agent startup** — `mcp.list_tools()` fails, agent has no tools
2. **Gateway dies mid-session** — `mcp.call_tool()` fails, user sees error
3. **Gateway temporarily slow** — timeout, user waits, retries manually

Without resilience, any gateway issue crashes or blocks the agent.

---

## Design

### 1. Gateway Health Endpoint

```
GET /health
→ 200 {"status": "ok", "tools_count": 12, "backends": {"postgres": "up", "mongodb": "up", "authorsuite": "down"}}
→ 503 {"status": "degraded", "backends": {"postgres": "up", "mongodb": "timeout"}}
```

Agent checks this at startup and periodically (optional background ping).

### 2. McpClient Resilience

Three layers in `mcp/client.py`:

```python
class McpClient:
    def __init__(self, gateway_url: str, api_key: str = ""):
        self.gateway_url = gateway_url.rstrip("/")
        self._client = httpx.AsyncClient(
            timeout=30.0,
            headers={"Authorization": f"Bearer {api_key}"} if api_key else {},
        )
        self._tools: list[McpTool] = []
        self._request_id = 0
        self._available = False       # gateway reachable?

    async def check_health(self) -> bool:
        """Check if gateway is reachable. Non-throwing."""
        try:
            resp = await self._client.get(f"{self.gateway_url}/health", timeout=5.0)
            self._available = resp.status_code == 200
        except (httpx.ConnectError, httpx.TimeoutException):
            self._available = False
        return self._available

    async def connect(self) -> bool:
        """Health check + tool discovery. Called at startup."""
        if not await self.check_health():
            logger.warning("MCP Gateway unreachable at %s — starting without tools", self.gateway_url)
            return False
        await self.list_tools()
        logger.info("MCP Gateway connected: %d tools available", len(self._tools))
        return True

    async def call_tool(self, name: str, arguments: dict) -> str:
        """Execute tool with retry. Returns error string on failure, never throws."""
        if not self._available:
            return json.dumps({"error": "MCP Gateway unavailable"})
        try:
            return await self._rpc_with_retry("tools/call", {
                "name": name, "arguments": arguments,
            })
        except McpUnavailableError:
            self._available = False
            return json.dumps({"error": "MCP Gateway connection lost"})

    async def _rpc_with_retry(self, method: str, params: dict, max_retries: int = 2) -> str:
        """JSON-RPC call with retry + exponential backoff."""
        last_error = None
        for attempt in range(max_retries + 1):
            try:
                return await self._rpc(method, params)
            except httpx.TimeoutException:
                last_error = "timeout"
                if attempt < max_retries:
                    delay = 1.0 * (2 ** attempt)   # 1s, 2s
                    logger.warning("MCP call %s timeout, retry %d/%d in %.0fs",
                                   method, attempt + 1, max_retries, delay)
                    await asyncio.sleep(delay)
            except httpx.ConnectError:
                raise McpUnavailableError("Gateway unreachable")
        raise McpUnavailableError(f"MCP call {method} failed after {max_retries + 1} attempts: {last_error}")

    async def _rpc(self, method: str, params: dict) -> str:
        """Single JSON-RPC request. Throws on HTTP/connection error."""
        self._request_id += 1
        resp = await self._client.post(
            f"{self.gateway_url}/mcp",
            json={"jsonrpc": "2.0", "method": method, "params": params, "id": self._request_id},
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get("error"):
            raise McpToolError(data["error"]["message"], data["error"].get("code"))
        result = data.get("result", {})
        # Extract text from tool call results
        if method == "tools/call":
            contents = result.get("content", [])
            texts = [c.get("text", "") for c in contents if c.get("type") == "text"]
            return "\n".join(texts)
        return result

class McpUnavailableError(Exception):
    """Gateway unreachable or connection lost."""

class McpToolError(Exception):
    """Tool execution error (gateway returned JSON-RPC error)."""
    def __init__(self, message: str, code: int | None = None):
        self.code = code
        super().__init__(message)
```

### 3. Graceful Degradation

Agent works in three modes depending on gateway state:

| Mode | Condition | Behavior |
|------|-----------|----------|
| **Full** | Gateway up, tools discovered | Commands + LLM tool_use work |
| **Conversation-only** | Gateway down at startup | LLM chat works, `/commands` return "tools unavailable", free-form tool_use disabled (no tools in SmartGate call) |
| **Degraded** | Gateway dies mid-session | Pending tool calls return error. Next `/command` attempts reconnect. LLM chat continues. |

Agent startup:
```python
mcp = McpClient(gateway_url=MCP_GATEWAY_URL)
connected = await mcp.connect()   # health + list_tools, never throws
if not connected:
    console.print("MCP Gateway unavailable — running in conversation-only mode", style="warning")
```

Agent loop adapts:
```python
async def run_agent_loop(system_prompt, messages, mcp):
    tool_definitions = mcp.get_tool_definitions() if mcp._available else []
    # ... SmartGate call with tools=tool_definitions (empty = no tools)
```

Command dispatch adapts:
```python
async def handle_input(session, user_input):
    if user_input.startswith("/") and not session.mcp._available:
        # Try reconnect before failing
        if await session.mcp.connect():
            pass  # reconnected — proceed normally
        else:
            yield TextBlock(content="MCP Gateway unavailable. Commands require tool access.", style="error")
            return
    # ... normal dispatch
```

### 4. Reconnection

Agent doesn't poll gateway. Instead, reconnects lazily on next command:

```
1. Gateway down at startup → conversation-only mode
2. User types /validate → agent tries mcp.connect()
3. If gateway back → reconnect, discover tools, execute command
4. If still down → "Gateway unavailable" error
```

No background polling thread. No heartbeat. Simple lazy reconnect.

---

## Error Messages to User

| Situation | User sees |
|-----------|-----------|
| Gateway down at startup | `MCP Gateway unavailable — running in conversation-only mode` |
| `/command` with gateway down | `MCP Gateway unavailable. Commands require tool access.` |
| Tool call timeout (retrying) | `Tool: query (retrying...)` |
| Tool call failed after retries | `Tool error: timeout after 3 attempts` |
| Gateway returns tool error | `Tool error: {gateway error message}` |
| Gateway reconnected | `MCP Gateway reconnected: 12 tools available` |

---

## Gateway Health Endpoint Contract

```
GET /health

Response 200:
{
  "status": "ok",
  "tools_count": 12,
  "backends": {
    "postgres": "up",
    "mongodb": "up",
    "authorsuite": "up"
  },
  "uptime_seconds": 3600
}

Response 503:
{
  "status": "degraded",
  "tools_count": 8,
  "backends": {
    "postgres": "up",
    "mongodb": "up",
    "authorsuite": "down"
  },
  "uptime_seconds": 3600
}
```

Agent treats both 200 and 503 as "gateway reachable" — degraded gateway still has some tools. Only connection failure = unavailable.

---

## Summary

| Layer | What | Where |
|-------|------|-------|
| Health check | `GET /health` before tool discovery | `McpClient.connect()` |
| Retry + backoff | 2 retries, 1s/2s delay on timeout | `McpClient._rpc_with_retry()` |
| Graceful degradation | Conversation-only mode when no gateway | `run_agent_loop()` + `handle_input()` |
| Lazy reconnect | Reconnect on next `/command`, not background poll | `handle_input()` |
| Error isolation | `call_tool()` never throws, returns error string | `McpClient.call_tool()` |
