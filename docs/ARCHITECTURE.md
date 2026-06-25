# NexusLink Architecture

This document describes how NexusLink is structured. For the build status and roadmap,
see `TASKS.md`; for the product vision, see `NexusLink_Specification.md`.

## Module Layout

NexusLink is a Maven multi-module (reactor) build. Modules depend "downward" only —
UI depends on protocols and core; protocols depend on core and the plugin API; nothing
depends on the UI except the app.

```
nexuslink-parent (pom)
├── nexuslink-plugin-api      — ProtocolConnector + ResourceExplorer SPI, ConnectionConfig
├── nexuslink-core            — EventBus, CacheRegistry (Caffeine), AppContext (DI),
│                               HistoryStore (SQLite + FTS5), ConnectionStore, ThemeManager
├── nexuslink-security        — CredentialVault (AES-256-GCM), VaultStore, VaultSession
├── nexuslink-protocol-http   — RestExecutionService, WebSocketService, SseService, GraphQLService
├── nexuslink-protocol-ai     — MCP client (JSON-RPC), AnthropicService (LLM)
├── nexuslink-protocol-db     — JdbcService + JdbcDriverRegistry (universal SQL client)
├── nexuslink-protocol-mongo  — MongoService (find/SQL/aggregate/CRUD/schema)
├── nexuslink-protocol-redis  — RedisService (Lettuce)
├── nexuslink-protocol-kafka  — KafkaService (admin/producer/consumer)
├── nexuslink-protocol-grpc   — GrpcService (reflection-based, unary)
├── nexuslink-protocol-sftp   — SftpService (Apache MINA SSHD)
├── nexuslink-protocol-ftp    — FtpService (Apache Commons Net)
├── nexuslink-protocol-s3     — S3Service (AWS SDK v2, S3-compatible)
├── nexuslink-protocol-azure  — AzureBlobService (Azure SDK)
├── nexuslink-protocol-gcs    — GcsService (Google Cloud Storage)
├── nexuslink-ui              — MainWindow shell, HelpDialog, protocol views, theming
└── nexuslink-app             — NexusLinkLauncher (JavaFX Application)
```

Reserved/empty protocol modules (`protocol-messaging`, `protocol-file`,
`protocol-enterprise`) exist as placeholders for JMS/MQTT/RabbitMQ/IBM-MQ/Solace — they are
not yet implemented and carry no source.

## Layering

```
┌─────────────────────────────────────────────────────────────┐
│ PRESENTATION (nexuslink-ui, nexuslink-app)                  │
│   MainWindow · Rest/WebSocket/Sse/GraphQL/Grpc/Sql/Mongo/   │
│   Redis/Kafka/Sftp/Ftp/S3/AzureBlob/Gcs/McpInspector/Llm    │
│   Views · ResourceExplorerView · HelpDialog · DiagramView   │
├─────────────────────────────────────────────────────────────┤
│ SERVICE (nexuslink-protocol-*)                              │
│   RestExecutionService · WebSocketService · SseService ·   │
│   GraphQLService · GrpcService · McpClient · AnthropicSvc · │
│   JdbcService · MongoService · RedisService · KafkaService ·│
│   SftpService · FtpService · S3/AzureBlob/GcsService        │
├─────────────────────────────────────────────────────────────┤
│ CORE (nexuslink-core, nexuslink-security)                  │
│   EventBus · CacheRegistry · AppContext · HistoryStore ·   │
│   ConnectionStore · ThemeManager · CredentialVault         │
├─────────────────────────────────────────────────────────────┤
│ SPI (nexuslink-plugin-api)                                  │
│   ProtocolConnector · ResourceExplorer · ConnectionConfig  │
└─────────────────────────────────────────────────────────────┘
```

## Key Patterns

**Background execution.** Every network/IO call runs on a JavaFX `Task` on a daemon
thread; results are applied on the FX thread via `setOnSucceeded`. The UI thread is never
blocked. Services (e.g. `RestExecutionService`, `JdbcService`) are plain blocking Java —
the threading lives in the view.

**Dependency injection.** `AppContext` is a hand-rolled singleton/prototype container.
We avoid Spring/Guice to sidestep JPMS module conflicts and keep startup instant.

**Caching.** `CacheRegistry` owns named Caffeine regions (DNS, schema registry, history
recent, help search, JDBC schema, …) with per-region TTLs. See the cache table in
`TASKS.md`.

**Events.** `EventBus` is a typed, weak-reference pub/sub. Listeners auto-clean when GC'd;
posting can be synchronous or on a virtual-thread executor.

**Help & context.** `HelpService` builds an in-memory inverted index over Markdown topics,
caches searches, and maps UI component IDs → help anchors so `F1` is context-sensitive.

## Data & Persistence

| Data | Store | Location |
|------|-------|----------|
| Request history | SQLite + FTS5 | `~/.nexuslink/history.db` |
| Credentials | AES-256-GCM JSON | `~/.nexuslink/vault.json` (master-password unlock, 5-min auto-lock) |
| Saved connections | JSON (secrets as vault refs) | `~/.nexuslink/connections.json` |
| On-demand JDBC drivers | downloaded jars | `~/.nexuslink/drivers/` |
| Preferences (theme, protocol visibility) | Java Preferences API | platform store |
| Caches | Caffeine (in-memory) | process memory |

## Protocol Service Contracts

Each protocol exposes a small, UI-agnostic service:

- **REST** — `RestExecutionService.execute(RestRequest) → RestResponse` (JDK `java.net.http`, HTTP/2)
- **WebSocket** — `WebSocketService` (JDK `java.net.http.WebSocket`), listener-based
- **SSE** — `SseService` streams `text/event-stream` with a per-event callback
- **GraphQL** — `GraphQLService` (HTTP POST `{query, variables}` + introspection)
- **gRPC** — `GrpcService` (managed channel, server reflection, unary `DynamicMessage` ↔ JSON)
- **MCP** — `McpClient` over a `McpTransport` (HTTP or stdio); JSON-RPC 2.0
- **LLM** — `AnthropicService.complete(model, system, user) → Result` (Anthropic Java SDK)
- **JDBC** — `JdbcService` (DriverManager + `JdbcDriverRegistry`); `connect`, `query`, `schema`
- **MongoDB** — `MongoService` (find/SQL/aggregate/explain/CRUD, schema inference)
- **Redis** — `RedisService` (Lettuce; SCAN, typed value read, command runner)
- **Kafka** — `KafkaService` (Admin discovery, producer, background-poll consumer)
- **File transfer** — `SftpService` / `FtpService` (list dir, read file)
- **Object storage** — `S3Service` / `AzureBlobService` / `GcsService` (list buckets/objects)

Many of these implement the **`ResourceExplorer`** SPI so their object trees render through
one shared `ResourceExplorerView` (lazy children + on-select details).

New protocols follow the same shape: a headless, testable service + a `*View` that drives
it on a `Task` and renders results.

## Testing Strategy

Services are designed to be testable without a UI or external infrastructure where
possible: the vault (pure crypto), the history store (embedded SQLite), the MCP client
(in-memory mock transport), the REST request/auth logic, the JDBC client + driver registry
(in-memory SQLite), and the MongoDB SQL translator all have unit tests. The MongoDB
integration tests use Testcontainers and are gated behind `-DrunMongoIT=true`, so the
default build stays green without Docker. Protocols that require live infrastructure (Kafka,
real MCP servers, live LLM calls) are validated against their service contracts and
exercised manually; several (REST/SSE/GraphQL/gRPC/SFTP/FTP/S3) were confirmed against real
public endpoints — see the Progress Log in `TASKS.md`.

## Adding a Protocol — Checklist

1. Create/enable the module and add it to the parent `<modules>` + `<dependencyManagement>`.
2. Write the headless service (`*Service`) + value types; add unit tests.
3. Write the `*View` (JavaFX, programmatic) that drives the service on a `Task`.
4. Wire it into `MainWindow` (menu item, sidebar button, `open*Tab()` method).
5. Add a help Markdown topic and register it in `HelpService`.
6. Update `TASKS.md` (check off items, add a progress-log entry).
