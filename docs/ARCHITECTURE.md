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
│                               HistoryStore (SQLite + FTS5), ConnectionStore, ThemeManager,
│                               EnvironmentService (${VAR} / .env / VariableInterpolator / masking)
├── nexuslink-security        — CredentialVault (AES-256-GCM), VaultStore, VaultSession,
│                               certificate manager (CertificateStore/Parser/Generator, ExpirationWatchdog)
├── nexuslink-protocol-http   — RestExecutionService, WebSocketService, SseService, GraphQLService
├── nexuslink-protocol-ai     — MCP client (JSON-RPC), AnthropicService (LLM)
├── nexuslink-protocol-db     — JdbcService + JdbcDriverRegistry (universal SQL client)
├── nexuslink-protocol-mongo  — MongoService (find/SQL/aggregate/CRUD/schema)
├── nexuslink-protocol-redis  — RedisService (Lettuce)
├── nexuslink-protocol-kafka  — KafkaService (admin/producer/consumer)
├── nexuslink-protocol-mqtt   — MqttService (Eclipse Paho; connect/subscribe/publish)
├── nexuslink-protocol-rabbitmq — RabbitMqService (amqp-client; declare/publish/consume)
├── nexuslink-protocol-ldap   — LdapService (UnboundID; connect/bind/search)
├── nexuslink-protocol-snmp   — SnmpService (SNMP4J; v1/v2c GET + WALK)
├── nexuslink-protocol-grpc   — GrpcService (reflection-based, unary)
├── nexuslink-protocol-sftp   — SftpService (Apache MINA SSHD)
├── nexuslink-protocol-ftp    — FtpService (Apache Commons Net)
├── nexuslink-protocol-s3     — S3Service (AWS SDK v2, S3-compatible)
├── nexuslink-protocol-azure  — AzureBlobService (Azure SDK)
├── nexuslink-protocol-gcs    — GcsService (Google Cloud Storage)
├── nexuslink-protocol-sqs    — SqsSnsService (AWS SQS + SNS)
├── nexuslink-protocol-jms    — JmsService (ActiveMQ / Artemis)
├── nexuslink-protocol-ibmmq  — MqService (IBM MQ client)
├── nexuslink-protocol-solace — SolaceService (Solace PubSub+)
├── nexuslink-protocol-servicebus — ServiceBusService (Azure Service Bus)
├── nexuslink-protocol-pubsub — PubSubService (Google Pub/Sub)
├── nexuslink-protocol-ssh    — SshService + VtScreen (SSH terminal)
├── nexuslink-protocol-secrets — external secret vaults (HashiCorp, Conjur, cloud)
├── nexuslink-ui              — MainWindow shell, HelpDialog, protocol views, theming
└── nexuslink-app             — NexusLinkLauncher (JavaFX Application)
```

Every protocol listed above is implemented and has its own module; the placeholder modules that
once stood in for the messaging and file protocols are gone. The rule the layout enforces is that a
protocol module owns its client library and its **pure** logic (parsers, planners, formatters,
diff/compare, dialect rules) and knows nothing about JavaFX, while `nexuslink-ui` owns the views and
depends on the protocol modules — never the other way round. That is what keeps the interesting
logic testable without a UI, and it is why most tests live in the protocol modules.

## Layering

```
┌─────────────────────────────────────────────────────────────┐
│ PRESENTATION (nexuslink-ui, nexuslink-app)                  │
│   MainWindow · Rest/WebSocket/Sse/GraphQL/Grpc/Sql/Mongo/   │
│   Redis/Kafka/Mqtt/Sftp/Ftp/S3/AzureBlob/Gcs/McpInspector/  │
│   Llm/CertificateManager/EnvironmentManager Views ·         │
│   ResourceExplorerView · HelpDialog · DiagramView           │
├─────────────────────────────────────────────────────────────┤
│ SERVICE (nexuslink-protocol-*)                              │
│   RestExecutionService · WebSocketService · SseService ·   │
│   GraphQLService · GrpcService · McpClient · AnthropicSvc · │
│   JdbcService · MongoService · RedisService · KafkaService ·│
│   MqttService · SftpService · FtpService · S3/Azure/GcsSvc  │
├─────────────────────────────────────────────────────────────┤
│ CORE (nexuslink-core, nexuslink-security)                  │
│   EventBus · CacheRegistry · AppContext · HistoryStore ·   │
│   ConnectionStore · ThemeManager · EnvironmentService ·    │
│   CredentialVault · CertificateStore · ExpirationWatchdog  │
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
| Environments (`${VAR}` sets + active id) | JSON | `~/.nexuslink/environments.json` (+ optional `~/.nexuslink/.env`) |
| On-demand JDBC drivers | downloaded jars | `~/.nexuslink/drivers/` |
| Driver repository settings (optional) | properties | `~/.nexuslink/maven.properties` |
| Preferences (theme, protocol visibility) | Java Preferences API | platform store |
| Caches | Caffeine (in-memory) | process memory |

### On-demand JDBC drivers behind a corporate proxy

Heavy or licensed drivers (Oracle, SQL Server, DB2, …) are not bundled; they are fetched by Maven
coordinates on first use. In an environment with no direct internet access, `ExternalDriverLoader`
resolves a jar in this order:

1. `~/.nexuslink/drivers/` — already downloaded,
2. the local Maven repository (`~/.m2/repository`, or `-Dmaven.repo.local`) — no network at all,
3. the configured remote repository.

`MavenRepositoryConfig` picks the remote repository from, highest precedence first:

| Source | Keys |
|--------|------|
| System properties | `nexuslink.maven.repoUrl`, `.username`, `.password`, `.token` |
| Environment | `NEXUSLINK_MAVEN_REPO_URL`, `NEXUSLINK_MAVEN_USERNAME`, `NEXUSLINK_MAVEN_PASSWORD`, `NEXUSLINK_MAVEN_TOKEN` |
| `~/.nexuslink/maven.properties` | `repoUrl`, `username`, `password`, `token` |
| `~/.m2/settings.xml` | first `<mirror>` URL + the matching `<server>` credentials |
| _(default)_ | Maven Central, unauthenticated |

A token is sent as `Authorization: Bearer …`, a username/password as basic auth. Downloads honour
the JVM proxy settings (`-Dhttps.proxyHost=… -Dhttps.proxyPort=…`, or
`-Djava.net.useSystemProxies=true`); a proxy or Artifactory using a private CA needs that CA in the
JVM trust store (`-Djavax.net.ssl.trustStore=…`). Passwords encrypted with Maven's
`settings-security.xml` are **not** decrypted — configure those explicitly via one of the sources
above. Fully air-gapped machines can skip all of this and drop the jar into
`~/.nexuslink/drivers/`, or pick it with **Browse for driver JAR…**.

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
- **MQTT** — `MqttService` (Eclipse Paho; connect, subscribe to topic filters, publish)
- **File transfer** — `SftpService` / `FtpService` (list dir, read file)
- **Object storage** — `S3Service` / `AzureBlobService` / `GcsService` (list buckets/objects)

Many of these implement the **`ResourceExplorer`** SPI so their object trees render through
one shared `ResourceExplorerView` (lazy children + on-select details).

New protocols follow the same shape: a headless, testable service + a `*View` that drives
it on a `Task` and renders results.

## Testing Strategy

Services are designed to be testable without a UI or external infrastructure where
possible: the vault (pure crypto), the certificate manager + `ExpirationWatchdog`
(clock-injectable), the environment-variable system (`VariableInterpolator` / `EnvironmentService`
/ `SecretMaskingFilter`), the history store (embedded SQLite), the MCP client (in-memory mock
transport), the REST request/auth logic, the JDBC client + driver registry (in-memory SQLite),
and the MongoDB SQL translator all have unit tests. The MongoDB integration tests use
Testcontainers and are gated behind `-DrunMongoIT=true`, so the default build stays green without
Docker. Protocols that require live infrastructure (Kafka, real MCP servers, live LLM calls) are
validated against their service contracts and exercised manually; several
(REST/SSE/GraphQL/gRPC/SFTP/FTP/S3/MQTT) were confirmed against real public endpoints — see the
Progress Log in `TASKS.md`.

### Live integration tests

Alongside the unit tests, the `test-env/` Docker Compose stack runs the real servers, and the
`*LiveIT` tests are gated behind `-Dnexuslink.it=true` so the default build needs no Docker:

```bash
cd test-env && docker compose up -d kafka mongo redis      # and the rest as needed
mvn test -Dnexuslink.it=true
```

These are what catch the failures unit tests cannot. Recent examples: a Kafka consumer that never
left its group after Stop (holding partitions until the session timeout), a Mongo change stream that
delivered one more event after being closed, Lettuce's `ObjectOutput` throwing on a top-level scalar,
and Maven publishing snapshots under a timestamped filename. The stack includes a single-node
**replica set** (`mongo-rs`, port 27018) because change streams and transactions cannot be exercised
against a standalone `mongod` at all.

## Adding a Protocol — Checklist

1. Create/enable the module and add it to the parent `<modules>` + `<dependencyManagement>`.
2. Write the headless service (`*Service`) + value types; add unit tests.
3. Write the `*View` (JavaFX, programmatic) that drives the service on a `Task`.
4. Wire it into `MainWindow` (menu item, sidebar button, `open*Tab()` method).
5. Add a help Markdown topic and register it in `HelpService`.
6. Add a live `*LiveIT` gated on `-Dnexuslink.it=true`, and a `test-env` service if it needs one.
7. Update `TASKS.md` (check off items, add a progress-log entry) and the relevant spec in
   `.agent-os/specs/`.
