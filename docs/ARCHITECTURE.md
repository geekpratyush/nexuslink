# NexusLink Architecture

This document describes how NexusLink is structured. For the build status and roadmap,
see `TASKS.md`; for the product vision, see `NexusLink_Specification.md`.

## Module Layout

NexusLink is a Maven multi-module (reactor) build. Modules depend "downward" only —
UI depends on protocols and core; protocols depend on core and the plugin API; nothing
depends on the UI except the app.

```
nexuslink-parent (pom)
├── nexuslink-plugin-api      — ProtocolConnector SPI, ConnectionConfig, descriptors
├── nexuslink-core            — EventBus, CacheRegistry (Caffeine), AppContext (DI),
│                               HistoryStore (SQLite + FTS5)
├── nexuslink-security        — CredentialVault (AES-256-GCM), VaultStore
├── nexuslink-protocol-http   — RestExecutionService, WebSocketService
├── nexuslink-protocol-ai     — MCP client (JSON-RPC), AnthropicService (LLM)
├── nexuslink-protocol-db     — JdbcService (universal SQL client)
├── nexuslink-ui              — MainWindow shell, HelpDialog, protocol views
└── nexuslink-app             — NexusLinkLauncher (JavaFX Application)
```

Empty/planned protocol modules (messaging, file, enterprise) are commented out of the
parent `<modules>` list until they have source — re-enable each as it is implemented.

## Layering

```
┌─────────────────────────────────────────────────────────────┐
│ PRESENTATION (nexuslink-ui, nexuslink-app)                  │
│   MainWindow · RestClientView · McpInspectorView ·          │
│   LlmTesterView · WebSocketView · SqlClientView · HelpDialog │
├─────────────────────────────────────────────────────────────┤
│ SERVICE (nexuslink-protocol-*)                              │
│   RestExecutionService · WebSocketService · McpClient ·     │
│   AnthropicService · JdbcService                            │
├─────────────────────────────────────────────────────────────┤
│ CORE (nexuslink-core, nexuslink-security)                  │
│   EventBus · CacheRegistry · AppContext · HistoryStore ·   │
│   CredentialVault                                           │
├─────────────────────────────────────────────────────────────┤
│ SPI (nexuslink-plugin-api)                                  │
│   ProtocolConnector · ConnectionConfig · PluginDescriptor   │
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
| Credentials | AES-256-GCM JSON | vault file (when wired into UI) |
| Caches | Caffeine (in-memory) | process memory |

## Protocol Service Contracts

Each protocol exposes a small, UI-agnostic service:

- **REST** — `RestExecutionService.execute(RestRequest) → RestResponse` (JDK `java.net.http`, HTTP/2)
- **WebSocket** — `WebSocketService` (JDK `java.net.http.WebSocket`), listener-based
- **MCP** — `McpClient` over a `McpTransport` (HTTP or stdio); JSON-RPC 2.0
- **LLM** — `AnthropicService.complete(model, system, user) → Result` (Anthropic Java SDK)
- **JDBC** — `JdbcService` (HikariCP-free first cut via DriverManager); `connect`, `query`, `schema`

New protocols follow the same shape: a headless, testable service + a `*View` that drives
it on a `Task` and renders results.

## Testing Strategy

Services are designed to be testable without a UI or external infrastructure where
possible: the vault (pure crypto), the history store (embedded SQLite), the MCP client
(in-memory mock transport), and the JDBC client (in-memory SQLite) all have unit tests.
Protocols that require live infrastructure (Kafka, MQTT, real MCP servers, live LLM calls)
are validated against their service contracts and exercised manually.

## Adding a Protocol — Checklist

1. Create/enable the module and add it to the parent `<modules>` + `<dependencyManagement>`.
2. Write the headless service (`*Service`) + value types; add unit tests.
3. Write the `*View` (JavaFX, programmatic) that drives the service on a `Task`.
4. Wire it into `MainWindow` (menu item, sidebar button, `open*Tab()` method).
5. Add a help Markdown topic and register it in `HelpService`.
6. Update `TASKS.md` (check off items, add a progress-log entry).
