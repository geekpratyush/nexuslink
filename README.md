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

NexusLink is under active development and **not yet feature-complete** against the full
specification (`NexusLink_Specification.md`). As of the latest session, roughly **48% of the
tracked tasks are done** (125 done · 28 in-progress · 100 not started), and the **Phase-1
foundations are now complete**. `TASKS.md` is the live, phase-by-phase tracker and the source
of truth; the table below summarizes it.

Legend: ✅ working · 🟡 partial / first cut · ⏳ not started

**Foundation & core**

| Area | Status |
|------|--------|
| Workspace shell (menu, connection tree, tabs, log, status bar) | ✅ Working |
| Help system (searchable 3-pane dialog, F1, context help, Markdown/Mermaid) | ✅ Working |
| Credential vault (AES-256-GCM, PBKDF2) + master-password dialog + auto-lock | ✅ Working, wired into saved connections |
| Request history (SQLite + FTS5, replay) | ✅ Working |
| Dark/light theming (palette variables, Ctrl+Shift+T) | ✅ Working (font bundling + system auto-detect TODO) |
| Per-user protocol visibility (View ▸ Protocols…) | ✅ Working |
| Connection profiles / saved connections | 🟡 Persisted + samples (folders/tags/import-export TODO) |
| Certificate manager (generate/import/export, PKCS12/JKS, expiry watchdog) | ✅ Working (DER/PKCS12 export + bundle import + CSR TODO) |
| Environment-variable system (`${VAR}` envs, `.env`, secret masking) | ✅ Working (per-view send-path adoption TODO) |

**Protocols**

| Protocol | Status |
|----------|--------|
| **REST** (HTTP/2, Basic/Bearer/API-key/OAuth2-client-creds, timing, viewers, code-gen) | ✅ Working (more auth flows + viewers TODO) |
| **WebSocket** | ✅ Working (text; binary/reconnect TODO) |
| **SSE** | ✅ Working (verified live) |
| **GraphQL** (query/variables/introspection) | ✅ Working (subscriptions TODO) |
| **gRPC** (reflection-based, unary) | ✅ Working (verified live; streaming TODO) |
| **JDBC SQL** (SQLite/H2/Postgres/MySQL/MariaDB bundled + on-demand driver mgr, ER diagram) | ✅ Working |
| **MongoDB** (find/SQL/aggregate/explain/CRUD, schema diagram, Compass views, export) | ✅ Working |
| **Redis** (Lettuce; key browser, typed values, command console) | 🟡 Built (needs live server for E2E) |
| **Kafka** (admin/produce/consume, topic explorer) | 🟡 First cut (needs a broker for E2E) |
| **SFTP / FTP / FTPS** (remote tree browse + read) | ✅ Working (verified live; local pane + transfer queue TODO) |
| **S3 / Azure Blob / GCS** object storage (bucket→object browser) | 🟡 S3 verified live; Azure/GCS need creds for E2E |
| **MCP Inspector** (tools/resources/prompts, Bearer-token auth) | ✅ Working (tested; OAuth/PKCE + vaulting TODO) |
| **AI Agent / LLM tester** (Anthropic SDK) | ✅ Working (needs `ANTHROPIC_API_KEY`) |
| **MQTT** (Eclipse Paho; connect/subscribe/publish) | 🟡 First cut (verified live vs. HiveMQ public broker) |
| RabbitMQ · JMS · IBM MQ · Solace · cloud messaging (SQS/SNS/Service Bus/Pub-Sub) | ⏳ Not started |
| LDAP / Active Directory · SSH terminal · SNMP | ⏳ Not started |

**Cross-cutting / polish**

| Area | Status |
|------|--------|
| Metrics dashboard · distributed tracing · team collaboration | ⏳ Not started |
| External secret vaults (HashiCorp/AWS/Azure/CyberArk) | ⏳ Not started |
| Global code generation SPI (beyond REST) | ⏳ Not started |
| Native packaging (`jlink` / `jpackage`, auto-update) | ⏳ Not started |

> **Short answer to "is it done?": no.** The Phase-1 foundation (vault, certificate manager,
> environment variables, history), the help infrastructure, and the most-used protocols
> (REST/WS/SSE/GraphQL/gRPC/SQL/Mongo/Redis/object-storage/MCP/LLM, plus a first-cut Kafka and
> MQTT) are built and many are verified live. Remaining enterprise messaging
> (RabbitMQ/JMS/MQ/Solace/cloud), directory services (LDAP/SSH/SNMP), monitoring/metrics,
> external vault integrations, and native installers are still ahead. See `TASKS.md` for the
> exact remaining items per phase.

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

- **REST** — all HTTP methods, params/headers/body editors, Basic/Bearer/API-key/OAuth2
  (client-credentials) auth, response viewers (JSON pretty-print, headers), per-request timing
  (total/TTFB/download), HTTP/2, and code generation (cURL/Python/JS/Java/PowerShell).
- **WebSocket / SSE / GraphQL / gRPC** — live WS message log; SSE event stream with filtering;
  GraphQL query/variables/introspection; reflection-based gRPC unary calls (no `.proto` upload).
- **SQL (JDBC)** — connect to any JDBC database, run queries, browse results, inspect schema,
  render an **ER diagram**. SQLite/H2/Postgres/MySQL/MariaDB bundled; others load on demand.
- **MongoDB** — find / SQL-like queries / aggregation pipeline builder / explain / CRUD,
  inferred **schema diagram**, Compass-style JSON/Table/Schema views, and JSON/CSV export.
- **Redis** — key browser with typed value rendering + a command console.
- **Kafka** — topic/partition explorer, produce, and consume (first cut; needs a broker).
- **MQTT** — connect to a broker, subscribe to topic filters, and publish (Eclipse Paho; first
  cut, verified live against the HiveMQ public broker).
- **File transfer** — SFTP / FTP / FTPS remote directory browsing and file read.
- **Object storage** — S3 / Azure Blob / GCS bucket→object browsers behind one shared view.
- **MCP Inspector** — connect to a Model Context Protocol server (HTTP or stdio), with optional
  **Bearer-token auth**; list and call its **tools**, read **resources**, render **prompts**.
- **AI Agent / LLM Tester** — send Messages API requests to Claude (default
  `claude-opus-4-8`, adaptive thinking) and inspect the response and token usage.
- **Vault** — AES-256-GCM credential vault with a master-password dialog and 5-min auto-lock;
  saved-connection secrets are stored as vault refs, never plaintext.
- **Certificate manager** — generate self-signed RSA/ECDSA certs, import/export PEM/DER, persist
  to a PKCS12/JKS keystore, and get colour-coded 30/7/1-day expiry warnings (expiry watchdog).
- **Environment variables** — named `${VAR}` environments (dev/staging/prod), a `.env` file, and
  system-env fallback, with secret values masked in the UI and scrubbed from logs.
- **History** — every request is persisted (SQLite + full-text search) and replayable.
- **Help** — press **F1** anywhere for a searchable, indexed, Markdown/Mermaid in-app help system.

## Architecture

NexusLink is a Maven multi-module project. See `docs/ARCHITECTURE.md` for details.

```
nexuslink-plugin-api        SPI for protocol connectors + object explorers
nexuslink-core              EventBus, Caffeine cache, DI, history store, connection profiles,
                            environment-variable system (${VAR} / .env / secret masking)
nexuslink-security          Credential vault (AES-256-GCM, PBKDF2), certificate manager + expiry watchdog
nexuslink-protocol-http     REST, WebSocket, SSE, GraphQL
nexuslink-protocol-ai       MCP client, Anthropic LLM tester
nexuslink-protocol-db       JDBC SQL client + driver manager
nexuslink-protocol-mongo    MongoDB client
nexuslink-protocol-redis    Redis client
nexuslink-protocol-kafka    Kafka client
nexuslink-protocol-mqtt     MQTT client (Eclipse Paho)
nexuslink-protocol-grpc     gRPC client (reflection-based)
nexuslink-protocol-sftp     SFTP client (Apache MINA SSHD)
nexuslink-protocol-ftp      FTP / FTPS client (Apache Commons Net)
nexuslink-protocol-s3       S3 / S3-compatible object storage (AWS SDK v2)
nexuslink-protocol-azure    Azure Blob Storage
nexuslink-protocol-gcs      Google Cloud Storage
nexuslink-protocol-messaging  (reserved — RabbitMQ/JMS, not yet implemented)
nexuslink-protocol-enterprise (reserved — IBM MQ/Solace, not yet implemented)
nexuslink-protocol-file       (reserved — additional file protocols)
nexuslink-ui                JavaFX shell, help system, protocol views, theming
nexuslink-app               Application entry point
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
the REST request/auth logic, the JDBC client + driver registry (against in-memory SQLite),
and the MongoDB SQL translator. The MongoDB integration tests use Testcontainers and are
gated behind `-DrunMongoIT=true`, so the default build stays green without Docker.
Live-verified protocols (REST/SSE/GraphQL/gRPC/SFTP/FTP/S3) were confirmed against real
public endpoints — see the Progress Log in `TASKS.md`.

## License

Proprietary — internal project. Framework: RouteForge.

---

*Author: Pratyush Ranjan Mishra.*
