# Project Vision

## Pitch
Kukuvaia is an open-source AI agent platform where tools are YAML files, not code. Add a new capability to your agent by dropping a markdown file — no Java, no TypeScript, no rebuild. Self-hosted, provider-agnostic, MCP-native.

## Problem Statement
Current AI coding agents (Claude Code, Cursor, Windsurf) are closed-source, cloud-locked, and require TypeScript/Python to extend. Adding a new tool means writing hundreds of lines of code, rebuilding, and redeploying. Enterprise teams need agents on their own infrastructure with custom tools — but existing solutions don't support that without significant engineering effort.

## Target Users
- **Developers** wanting a self-hosted AI agent with custom tools for their workflow
- **Teams** needing an agent platform on their infrastructure (data stays in-house)
- **DevOps/Platform engineers** automating tasks with AI + custom tooling
- **Open-source contributors** building and sharing reusable tools

## Key Differentiators

1. **Tools as YAML+Lua** — 20 lines of YAML vs 1183 lines of TypeScript (Claude Code FileReadTool). No code to write, no rebuild, no restart.

2. **Hot reload** — drop a TOOL.md file, agent picks it up immediately. Per-tool config.yaml for API keys.

3. **Provider agnostic** — 3 env vars to switch between SmartGate, OpenAI, Copilot, Claude, or any OpenAI-compatible API. No vendor lock-in.

4. **MCP native** — server is both MCP server (exposes tools) and MCP client (connects to external tool servers). CLI is also MCP server (local filesystem tools). Everything interconnects via standard protocol.

5. **Self-hosted** — `docker-compose up` and you have a running agent. Your data, your infrastructure, your rules.

6. **Structured rules** — control LLM behavior via markdown files with YAML frontmatter. Scope rules per persona, per intent, with priorities.

## Current State
- **Server**: Java 21 / Spring Boot 3.4.4 / Spring AI 1.1.0 — production code implemented
- **Tools**: YAML+Lua pipeline engine with hot reload and per-tool config
- **Tests**: 136 test methods, all passing
- **CLI**: Implementation plan ready (Go / Charm stack / MCP Server)
- **Stage**: Pre-release, preparing for open-source launch

## Goals (Next 3 Months)
- Ship CLI v0.1 (MCP Server + minimal TUI)
- Publish on GitHub with README, docker-compose, Apache 2.0 license
- Build 10+ example tools (web search, git, JSON, CSV, shell)
- Get first external contributor

## Evolution
Originally prototyped as sl-content-engine (ETSL) — a Python-based agent for content management. Rewritten in Java/Spring Boot + Spring AI for type safety, enterprise-grade security, and the composable advisor architecture. Tool system evolved from hardcoded Java @Tool classes → YAML+Lua pipeline engine (inspired by but simpler than Claude Code's approach).

---
*Updated: 2026-04-10*
