# AI Agent (MCP tools)

The **Agent** tab is the "agent testing" endgame: it connects to a Model Context Protocol (MCP)
server, hands that server's **tools** to Claude, and runs the full **tool-calling loop** — the model
plans, calls tools, sees their results, and keeps going until it produces a final answer. You watch
every step stream into a live transcript.

## Prerequisites

- **`ANTHROPIC_API_KEY`** in the environment (the status line turns green when detected).
- An **MCP server** to expose tools — either an HTTP (Streamable) endpoint or a stdio subprocess
  command (e.g. `npx -y @modelcontextprotocol/server-everything`).

## Running an agent

1. Pick a **transport** (HTTP or stdio) and enter the server **target**; for HTTP you may add a
   **Bearer token**. Click **Connect** — the tool count appears once the handshake succeeds.
2. Choose a **model** (default `claude-opus-4-8`).
3. Optionally set a **system prompt** to shape behaviour, then describe the **task**.
4. Click **Run agent**. The transcript shows each turn:
   - 🤖 assistant text (the model's reasoning/answer)
   - 🔧 a tool call with its JSON arguments
   - ↳ the tool result (⚠ if the tool reported an error)
   - ✓ a final summary with turn count, tool calls, and token usage.

The loop stops when the model answers without calling a tool, or after a safety cap of 12 turns.

## How it works

Each MCP tool's JSON-Schema `inputSchema` is converted into an Anthropic tool definition, so the
model knows exactly what arguments each tool takes. When the model emits `tool_use` blocks, NexusLink
executes them against the MCP server and feeds the results back as `tool_result` blocks — preserving
the model's thinking and tool-use blocks verbatim between turns. Field values support `${VAR}`
substitution from the active environment.

## Tips

- Use the **MCP Inspector** tab first to browse a server's tools and confirm it connects.
- Keep tasks concrete ("read file X and summarise it") so the model picks the right tools.
- If a tool fails, its error is shown to the model, which can retry or change approach.

## Not yet supported

Multi-turn chat (the task is a single instruction), human-in-the-loop approval before each tool
call, and parallel tool execution are on the roadmap.
