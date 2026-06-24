# NexusLink — Universal Connectivity Workbench

> **One Console. Every Protocol. Zero Context Switching.**
> A professional-grade JavaFX desktop workbench for testing, debugging, and managing
> connections across every major protocol used in modern distributed systems —
> REST, Kafka, MQTT, SFTP, gRPC, databases, **Model Context Protocol**, and more.

[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![JavaFX](https://img.shields.io/badge/JavaFX-21-blue)]()
[![Build](https://img.shields.io/badge/build-Maven-green)]()

---

## What is NexusLink?

NexusLink consolidates the functionality of disparate tools — Postman, Kafka Tool,
FileZilla, MQTT Explorer, a SQL client, and an MCP inspector — into a single, cohesive,
extensible platform. It eliminates context switching by providing a unified interface
with **one connection tree, one credential vault, one environment-variable system, and
one request history** shared across every protocol.

It also goes where Postman can't: **a built-in Model Context Protocol (MCP) inspector and
an AI-agent / LLM tester**, so you can exercise MCP servers and Claude models in the same
workbench you use for REST and Kafka.

## Current Status

NexusLink is under active development. This is **not yet feature-complete** against the
full specification (`NexusLink_Specification.md`) — see the status table below and
`TASKS.md` for the live, phase-by-phase tracker.

| Area | Status |
|------|--------|
| Workspace shell (menu, connection tree, tabs, log, status bar) | ✅ Working |
| Help system (searchable 3-pane dialog, F1, context help) | ✅ Working |
| Credential vault (AES-256-GCM, PBKDF2) | ✅ Core + tests (UI pending) |
| Request history (SQLite + FTS5, replay) | ✅ Working |
| **REST client** (HTTP/2, auth, timing, viewers, code path) | ✅ Working |
| **WebSocket client** | ✅ Working |
| **JDBC SQL client** (SQLite/H2/Postgres/MySQL…) | ✅ Working |
| **MCP Inspector** (tools / resources / prompts) | ✅ Working (tested) |
| **AI Agent / LLM tester** (Anthropic SDK) | ✅ Working (needs API key) |
| Kafka, MQTT, gRPC, SFTP, Redis, Mongo, LDAP, SNMP, … | ⏳ Planned (see TASKS.md) |
| Certificate manager, OAuth flows, external vaults | ⏳ Planned |
| Dark/light theming, native packaging | ⏳ Partial / planned |

## Requirements

- **Java 21** (`java -version` → 21.x) — the core uses virtual threads
- **Maven 3.9+**
- A graphical display (X11/Wayland) — this is a desktop GUI, not headless
- Optional: `ANTHROPIC_API_KEY` for the LLM tester; an MCP server for live MCP testing

## Quick Start

```bash
git clone <repo> && cd nexuslink
mvn -DskipTests install      # build all modules
cd nexuslink-app
mvn javafx:run               # launch the workbench
```

See **`RUN.md`** for a direct-`java` launch option and troubleshooting.

## Features at a Glance

- **REST** — all HTTP methods, params/headers/body editors, Basic/Bearer auth, response
  viewers (JSON pretty-print, headers), per-request timing (total/TTFB/download), HTTP/2.
- **WebSocket** — connect, send text/binary, live message log with direction + timestamps.
- **SQL (JDBC)** — connect to any JDBC database, run queries, browse results in a grid,
  inspect schema. Ships ready for SQLite/H2; add the driver jar for others.
- **MCP Inspector** — connect to a Model Context Protocol server (HTTP or stdio), list and
  call its **tools**, read its **resources**, and render its **prompts**.
- **AI Agent / LLM Tester** — send Messages API requests to Claude (default
  `claude-opus-4-8`, adaptive thinking) and inspect the response and token usage.
- **History** — every request is persisted (SQLite + full-text search) and replayable.
- **Help** — press **F1** anywhere for a searchable, indexed, in-app help system.

## Architecture

NexusLink is a Maven multi-module project. See `docs/ARCHITECTURE.md` for details.

```
nexuslink-plugin-api      SPI for protocol connectors
nexuslink-core            EventBus, Caffeine cache, DI, history store
nexuslink-security        Credential vault (AES-256-GCM), certificates
nexuslink-protocol-http   REST, WebSocket, SSE
nexuslink-protocol-ai     MCP client, Anthropic LLM tester
nexuslink-protocol-db     JDBC SQL client
nexuslink-ui              JavaFX shell, help system, protocol views
nexuslink-app             Application entry point
```

**Patterns:** MVVM-ish (view → service), background work on JavaFX `Task` (UI never blocks),
hand-rolled DI to avoid JPMS conflicts, Caffeine caching per the strategy in `TASKS.md`.

## Project Documents

| File | Purpose |
|------|---------|
| `README.md` | This file — overview and quick start |
| `NexusLink_Specification.md` | The full product specification (the north star) |
| `TASKS.md` | **Living** build tracker — phase-by-phase status, resume point, decisions |
| `RUN.md` | How to build and run, plus environment notes |
| `docs/ARCHITECTURE.md` | Module layout, patterns, data flow |

## Testing

```bash
mvn test          # run all unit tests
```

Current coverage focuses on the verifiable core: credential vault crypto round-trips,
SQLite history persistence + FTS search, the MCP JSON-RPC client (against a mock server),
and the JDBC client (against in-memory SQLite).

## License

Proprietary — internal project. Framework: RouteForge.

---

*Author: Pratyush Ranjan Mishra.*
