# NexusLink — Master Build Task Document

> **Living Document** — Update status inline as work progresses.  
> **On context loss:** Resume by reading this file first, then check `src/` for what exists.  
> **Convention:** `[x]` done · `[-]` in-progress · `[ ]` not started · `[!]` blocked

---

## HOW TO RESUME AFTER TOKEN EXPIRY

1. Read this file (`TASKS.md`) completely
2. Run `find src/ -name "*.java" | head -60` to see what exists
3. Check `PROGRESS_LOG.md` for the last session's notes
4. Continue from the first `[ ]` task in the current active phase

---

## PROJECT SNAPSHOT

| Item | Value |
|------|-------|
| Language | Java 17+ LTS |
| UI | JavaFX 21+, FXML, CSS |
| Build | Maven 3.9+ |
| Pattern | MVVM (FXML → ViewModel → Service) |
| Modules | JPMS modular |
| DB | SQLite (history), AES-256-GCM encrypted JSON (profiles/vault) |
| Cache | Caffeine (in-memory) |
| Spec | `NexusLink_Specification.md` |

---

## PHASE 0 — PROJECT SCAFFOLD ✦ START HERE

**Goal:** Maven multi-module project, JPMS modules, base infrastructure

### 0.1 Maven Project Structure
- [x] Create root `pom.xml` with all dependency versions in `<dependencyManagement>`
- [x] Create module POMs: `core`, `ui`, `security`, `protocol-http`, `protocol-messaging`, `protocol-file`, `protocol-db`, `protocol-enterprise`, `plugin-api`
- [ ] Configure `jlink` + `jpackage` in root POM
- [ ] Add `.gitignore`, `README.md` skeleton

### 0.2 Core Module (`nexuslink.core`)
- [ ] `module-info.java` for `nexuslink.core`
- [x] `EventBus` — lightweight pub/sub (typed events, weak listeners)
- [x] `AppContext` — singleton DI container (no Spring; hand-rolled)
- [x] `CaffeineCache` wrapper (`CacheRegion`) — typed, configurable TTL per cache region
- [x] `CacheRegistry` — all 10 standard cache regions pre-registered
- [ ] `ApplicationConfig` — Java Preferences API + JSON overlay
- [-] `ThemeManager` — dark/light toggle + persistence done (`nexuslink-ui/theme`); _system auto-detect TODO_
- [ ] `SettingsService` — read/write user preferences with defaults

### 0.3 Plugin API Module (`nexuslink.plugin.api`)
- [x] `ProtocolConnector` SPI interface
- [ ] `ProtocolView` SPI (returns JavaFX Node)
- [x] `PluginDescriptor` metadata record
- [x] `ConnectionConfig` — protocol-agnostic config bag with vault refs
- [x] `ValidationResult`, `ConnectionResult` records
- [ ] `ExtensionRegistry` — discovers and loads plugins via ServiceLoader

### 0.4 Base UI Module (`nexuslink.ui`)
- [ ] `module-info.java` for `nexuslink.ui`
- [x] `NexusLinkLauncher.java` — JavaFX Application entry point (opens MainWindow)
- [x] `MainWindow.java` — shell layout (programmatic, not FXML)
- [x] `ConnectionTreePanel` — left sidebar tree (basic; folders/tags/color/DnD still TODO)
- [x] `TabWorkspace` — center tab host (detachable still TODO)
- [ ] `PropertiesPanel` — right sidebar
- [x] `StatusBar` — open-tab count, version (memory/conn-status TODO)
- [x] `LogPanel` — collapsible bottom (level/protocol filters TODO)
- [x] `app-dark.css` — dark theme
- [x] Light theme CSS (`theme-light.css`) + ThemeManager toggle (palette-variable system; Ctrl+Shift+T; Preferences-persisted)
- [ ] Font loading — Inter + JetBrains Mono bundled (currently system fallback)

---

## PHASE 1 — FOUNDATION INFRASTRUCTURE

**Goal:** Security, vault, cert manager, env vars, connection profiles

### 1.1 Credential Vault (`nexuslink.security`)
- [x] `CredentialVault` service — AES-256-GCM, PBKDF2 key derivation (200k iterations)
- [x] `VaultStore` — JSON persistence (salt + per-secret ciphertext)
- [x] `MasterPasswordDialog` — create (confirm + strength hint) / unlock (retry on bad password) (`nexuslink-ui/vault`)
- [x] `AutoLockService` — built into `VaultSession` (5-min inactivity auto-lock, resets on use; manual Lock/Unlock in Tools menu + status-bar 🔒/🔓 toggle)
- [ ] `VaultBackupService` — encrypted export/import (VaultStore is the basis)
- [x] Unit tests for encryption round-trip (5/5 pass)
- [x] **Wire vault into saved connections** — SQL passwords & credentialed Mongo URIs are stored as vault refs (`passwordRef`/`targetRef`), never plaintext in `connections.json`; resolved on open. _REST AuthTab vaulting still TODO._

### 1.2 Certificate Manager
- [ ] `CertificateStore` — JKS/PKCS12 storage, AES-256 master key
- [ ] `CertificateParser` — X.509 field extraction (subject, issuer, SAN, key usage, extensions)
- [ ] `CertificateGenerator` — self-signed RSA/ECDSA with configurable validity + SAN
- [ ] `CertificateImporter` — PEM/DER/PKCS12/JKS drag-and-drop
- [ ] `CertificateExporter` — PEM/DER/PKCS12 with optional password
- [ ] `ExpirationWatchdog` — background thread, fires events at 30/7/1 day
- [ ] `CertificateManagerView.fxml` — list + detail panel + import wizard
- [ ] Certificate list with status icons (valid=green, warning=amber, expired=red)
- [ ] Unit tests: parse real certs, generate + verify chain

### 1.3 Connection Profile Manager
- [x] `ConnectionProfile` model — name, protocol, target, username, `AuthMethod` + auth/property maps, sample flag (`nexuslink-core/connection`)
- [-] `ProfileRepository` — `ConnectionStore` persists saved profiles + hidden-sample ids to `~/.nexuslink/connections.json`, CRUD; _encryption / secret-vault refs TODO_
- [ ] `ProfileImportExport` — encrypted JSON bundle, team share link
- [-] `ConnectionTreeView` — `ConnectionsPanel`: Saved + Samples groups, protocol icons, open/delete/hide; _folders, tags, color dots, drag-to-reorder TODO_
- [x] **Bundled public sample catalog** (`SampleCatalog`) — deletable/hideable public test endpoints (REST/WS/SQL/Mongo/MCP/LLM + SFTP/Kafka placeholders)
- [ ] `ProfileEditorDialog` — generic fields + protocol-specific section (pluggable)
- [ ] `ProfileValidator` — per-protocol pre-save validation

### 1.4 Environment Variable System
- [ ] `EnvironmentService` — merge system env + `.env` file + profile vars
- [ ] `VariableInterpolator` — `${VAR}` substitution across all string fields
- [ ] `EnvEditorDialog` — per-profile variable sets (dev/staging/prod)
- [ ] `SecretMaskingFilter` — mask in logs and UI, reveal toggle

### 1.5 History Store
- [x] `HistoryStore` — SQLite with FTS5 (full-text search) + LIKE fallback
- [x] `HistoryEntry` model — protocol, timestamp, summary, status, duration, favorite, replay detail
- [x] Search service — FTS query + limit (built into HistoryStore.search)
- [x] `HistoryPanel` — timeline list, search bar, replay button, favorite star
- [x] Caffeine cache: `history-recent` region mirrors recent entries
- [x] Unit tests (5/5 pass, incl. persistence across reopen)

---

## PHASE 2 — HELP SYSTEM (Build Early — Guides Everything)

**Goal:** A world-class, searchable, indexed help system embedded in the app

> **Design Goal:** User should never need to leave the app to understand a feature.  
> Help is searchable, contextual, keyboard-navigable, and always one keystroke away (`F1`).

### 2.1 Help Content Structure
- [x] `help/` resource directory with Markdown files per topic
- [x] Topics registered in `HelpService` (17 topics with keywords)
- [ ] Help topics to author (Markdown):
  - [x] `getting-started.md` — first-run guide, connection wizard walkthrough
  - [ ] `rest-client.md` — all request/response features
  - [ ] `kafka-client.md` — producer, consumer, admin, schema registry
  - [ ] `security.md` — TLS, mTLS, OAuth, Kerberos, vault
  - [ ] `environment-vars.md` — variable substitution, .env files
  - [ ] `certificate-manager.md` — import, generate, expiry
  - [x] `keyboard-shortcuts.md` — all shortcuts, searchable cheat sheet
  - [ ] `code-generation.md` — how to use code gen per protocol
  - [ ] `plugins.md` — writing custom protocol plugins
  - [ ] `troubleshooting.md` — common errors with fix suggestions
  - [ ] `mqtt.md`, `grpc.md`, `graphql.md`, `sftp.md`, `databases.md`, `ldap.md`, `snmp.md`

### 2.2 Help Engine
- [x] `HelpIndex` — in-memory inverted index with multi-term AND search + prefix fuzzy matching
- [x] `HelpService` — Caffeine-cached search (24h TTL), context mappings (19 UI components), recently viewed, content loader
- [ ] `HelpRenderer` — richer Markdown → JavaFX Node (tables, code blocks, inline links)
- [x] `ContextHelpResolver` — built into `HelpService.contextTarget(componentId)`

### 2.3 Help Dialog (`F1` / `Help` menu / `?` icon anywhere)
- [x] `HelpDialog.java` — non-blocking stage (users can work while reading)
- [x] **Three-pane layout** — topic tree (left) + rendered content (center) + section nav (right)
- [x] **Search bar** — live debounced search (150ms), highlighted excerpts with `<<word>>` markers
- [x] **Context-sensitive open** — `HelpDialog.openContextual(componentId)` resolves to best anchor
- [x] **"Did you know?" tips** — 10 rotating tips with fade animation
- [x] **Recently viewed** topics — tracked in `HelpService`, shown in tree
- [ ] `HelpButton` reusable component — `?` icon for any panel
- [x] Smooth close animation (fade 150ms)
- [x] `help-dialog.css` — full dark theme styling

### 2.4 In-App Contextual Hints
- [ ] Tooltip-plus system: hover on any field → shows field purpose + `F1 for more`
- [ ] `ErrorHelpLink` — errors include "What does this mean?" that opens help at the error code
- [ ] First-run onboarding overlay — step-by-step with "skip" and "don't show again"
- [ ] Empty-state illustrations — each empty panel shows a helpful "Get started" message

---

## PHASE 3 — HTTP CORE

**Goal:** REST client, WebSocket, SSE — the most-used features first

### 3.1 REST Client
- [x] `RestRequest` model — method, URL, params, headers, body, auth config, timeout settings
- [x] `RestExecutionService` — JDK `java.net.http` (HTTP/2), runs on background thread (`Task<RestResponse>`)
      _(note: spec said OkHttp; JDK client chosen for zero-dep first cut — swap later for per-phase DNS/TCP/TLS timing via EventListener)_
- [x] `RestClientView` — method dropdown, URL bar, tabbed panel (programmatic, not FXML)
- [x] `ParamsTab` — key/value table, enable toggle, auto URL-encoding, auto trailing row
- [x] `HeadersTab` — key/value table _(presets + history auto-complete TODO)_
- [-] `AuthTab` — auth type selector; sub-panels per type:
  - [x] Basic Auth
  - [x] Bearer Token (manual)
  - [x] API Key (header / query placement) — applied in `RestExecutionService`; 4/4 `RestRequestTest` pass _(cookie placement TODO)_
  - [-] OAuth 2.0 — **client-credentials grant** done (`OAuth2TokenClient` with token caching/auto-refresh, Auth-tab fields, applied in `RestExecutionService`); _authorization-code / PKCS / implicit / password flows TODO_
  - [ ] Digest, NTLM, AWS SigV4, HMAC, Custom Script
- [-] `BodyTab` — type selector: NONE/JSON/XML/TEXT/FORM_URLENCODED done; Form-Data/GraphQL/File TODO
  - [x] Body text editor with JSON format button _(RichTextFX syntax highlight TODO)_
  - [ ] Form-Data table with file picker per row
- [ ] `PreRequestTab` — JavaScript/Groovy script editor
- [x] `SettingsTab` — UI tab wiring connect/read timeouts + follow-redirects to the request (honored per-call by `RestExecutionService`) _(TLS/cert selection TODO)_
- [-] Response panel:
  - [x] Status badge (color-coded: 2xx green, 3xx blue, 4xx amber, 5xx red, err red)
  - [x] Timing: total, TTFB, download shown _(DNS/TCP/TLS split needs OkHttp listener)_
  - [-] `BodyViewer` — raw + auto JSON pretty-print done; XML tree/HTML/image/hex TODO
  - [x] `HeadersViewer` — text view _(sortable table TODO)_
  - [ ] `CookiesViewer` — cookie jar browser
  - [ ] `TimelineViewer` — waterfall chart (DNS/TCP/TLS/Send/Wait/Receive)
  - [ ] `TestResultsPanel` — post-response script pass/fail assertions
- [x] Code generation panel — `RestCodeGenerator` + `CodeGenDialog` (cURL / Python / JavaScript / Java / PowerShell), copy-to-clipboard; `</>` button on the REST bar _(Go TODO)_
- [ ] Request history sidebar integration
- [ ] Caffeine cache: DNS cache (TTL=30s), TLS session cache (TTL=300s)

### 3.2 WebSocket Client
- [x] `WebSocketService` — JDK `java.net.http.WebSocket`, text frame reassembly _(auto-reconnect TODO)_
- [x] `WebSocketView` — URL bar, connect/disconnect, message log, send bar
- [x] Message log: direction arrow, timestamp, content (text) _(binary/ping/pong TODO)_
- [-] Send panel: text send done _(binary toggle, file send, repeat/interval TODO)_

### 3.3 SSE Client
- [ ] `SseService` — OkHttp EventSource
- [ ] `SseView.fxml` — URL, headers, live event stream, event type filter, pause/resume

---

## PHASE 4 — KAFKA CLIENT

**Goal:** Full Kafka tooling — producer, consumer, admin, schema registry, monitoring

### 4.1 Connection
- [ ] `KafkaConnectionProfile` — bootstrap servers, security protocol, SASL mechanism, SSL config
- [ ] `KafkaConnectionService` — AdminClient singleton per profile, health-check ping
- [ ] Connection wizard with per-step diagnostics (DNS → TCP → TLS → SASL → Admin API)

### 4.2 Topic Browser & Admin
- [ ] `TopicTreeView` — list all topics, partition count, replica factor, configs
- [ ] `TopicDetailPanel` — configs table, partition map, leader/replica info
- [ ] Create/delete/alter topic dialog
- [ ] Partition reassignment UI

### 4.3 Producer
- [ ] `KafkaProducerService` — async send, idempotent option, transactions
- [ ] `ProducerView.fxml` — topic picker, partition (auto/manual), key editor, value editor, headers table
- [ ] Send result display: offset, partition, timestamp, latency

### 4.4 Consumer
- [ ] `KafkaConsumerService` — consumer group, configurable poll loop on background thread
- [ ] `ConsumerView.fxml` — group ID, topic subscription, offset reset, live message table
- [ ] Message table: offset, partition, timestamp, key, value (deserialized), headers
- [ ] Deserializer selector per key/value: String/JSON/Avro/Protobuf/Hex/Base64

### 4.5 Message Browser
- [ ] Poll-based browser (no consumer group side effects)
- [ ] Filters: offset range, timestamp range, key contains, value contains, header filter
- [ ] Export selected messages as JSON/CSV

### 4.6 Consumer Group Monitor
- [ ] `ConsumerLagService` — polls AdminClient on 5s interval (Caffeine cache)
- [ ] Lag table: group, topic, partition, committed offset, end offset, lag
- [ ] Lag chart: real-time line chart per partition over time
- [ ] Offset reset dialog: earliest/latest/specific timestamp/specific offset

### 4.7 Schema Registry
- [ ] `SchemaRegistryService` — Confluent and Apicurio REST API client (Caffeine cached, TTL=60s)
- [ ] Subject list, version history, schema viewer (Avro/Protobuf/JSON Schema)
- [ ] Compatibility mode display + change dialog
- [ ] Schema evolution diff (side-by-side version compare)

### 4.8 Kafka Metrics
- [ ] `KafkaMetricsService` — polls JMX or AdminClient metrics on 10s interval
- [ ] Throughput chart (msgs/sec, bytes/sec), error rate, partition count
- [ ] Consumer lag summary heatmap

---

## PHASE 5 — ENTERPRISE MESSAGING

### 5.1 JMS Generic Client
- [ ] `JmsConnectionWizard` — provider dropdown, connection factory class, JNDI config, JAR upload
- [ ] `JmsProducerService` + `JmsConsumerService`
- [ ] Message type selector: Text/Bytes/Map/Object/Stream
- [ ] Message properties editor (JMS standard + custom)

### 5.2 IBM MQ
- [ ] `MQConnectionProfile` — QM, channel, host, port, TLS, AMS
- [ ] `MQNativeService` — com.ibm.mq.allclient
- [ ] Queue browser (peek), put/get, DLQ inspection
- [ ] RFH2 header parser + display

### 5.3 Solace PubSub+
- [ ] `SolaceConnectionProfile` — VPN, host list, auth
- [ ] `SolaceJcsmpService` — JCSMP session + guaranteed/direct messaging
- [ ] Topic/queue browser, publish/subscribe UI
- [ ] Replay from log cache

### 5.4 MQTT
- [ ] `MqttService` — Eclipse Paho, v3.1.1 + v5.0
- [ ] `MqttView.fxml` — broker URL, client ID, QoS selector, topic subscribe/publish
- [ ] v5 properties: user properties, message expiry, content type, correlation data
- [ ] Message history with timestamp, QoS, retained flag

### 5.5 RabbitMQ
- [ ] `RabbitMqService` — AMQP 0.9.1 client + Management REST API
- [ ] Exchange/Queue/Binding browser, publish + consume UI
- [ ] DLX config viewer

### 5.6 Cloud Messaging
- [ ] AWS SQS: send/receive/delete, DLQ, FIFO support
- [ ] AWS SNS: publish, subscription listing
- [ ] Azure Service Bus: queue/topic/subscription, sessions, DLQ
- [ ] Google Pub/Sub: publish, pull subscription

---

## PHASE 6 — ADVANCED HTTP PROTOCOLS

### 6.1 gRPC Client
- [ ] `ProtoFileLoader` — parse `.proto` files, resolve imports, extract services/methods
- [ ] `GrpcChannelService` — managed channel per connection, TLS/mTLS
- [ ] `GrpcInvokerService` — unary, server stream, client stream, bidi stream
- [ ] `GrpcView.fxml` — service picker, method picker, request JSON editor, response panel
- [ ] Server reflection support (auto-discover services without proto file)
- [ ] Streaming panel: live message list, send message (client/bidi), end stream

### 6.2 GraphQL Client
- [ ] `GraphQLService` — HTTP + WebSocket (subscriptions)
- [ ] Schema introspection + type explorer tree
- [ ] Query/mutation editor with schema-aware auto-complete (CodeMirror port or RichTextFX)
- [ ] Variables JSON editor with schema validation
- [ ] Subscription live stream panel

---

## PHASE 7 — FILE TRANSFER

### 7.1 SFTP / SCP
- [ ] `SftpService` — Apache MINA SSHD, all auth methods
- [ ] `DualPaneBrowser.fxml` — local tree (left) + remote tree (right), drag-and-drop transfer
- [ ] `TransferQueue` — batch ops, pause/resume/retry/cancel, bandwidth throttle
- [ ] `SyncService` — bidirectional sync with conflict resolution (hash compare)
- [ ] Remote chmod + permissions display

### 7.2 FTP / FTPS
- [ ] `FtpService` — Apache Commons Net, active/passive, ASCII/binary, FTPS
- [ ] Integrated into dual-pane browser

### 7.3 Object Storage
- [ ] `S3Service` — AWS SDK v2, multipart upload, presigned URLs, versioning
- [ ] `AzureBlobService` — Azure SDK, SAS tokens, tiering
- [ ] `GcsService` — Google Cloud Storage client, signed URLs
- [ ] Shared bucket/container browser view

---

## PHASE 8 — DATABASE & ENTERPRISE PROTOCOLS

### 8.1 JDBC SQL Client
- [x] `JdbcService` — DriverManager connection, SELECT/update detection _(HikariCP pool TODO)_
- [x] `SqlClientView` — SQL editor (Ctrl+Enter), run button, result grid
- [x] Schema browser — `JdbcExplorer` + `ResourceExplorerView` lazy tree (database → tables/views → columns, types in details; double-click a table to query) _(indexes/procedures tree TODO)_
- [-] Result grid: rendered _(sort/filter/JSON/CSV export TODO)_
- [x] **4/4 unit tests pass** (in-memory SQLite)
- [ ] Query history integration (reuse HistoryStore)
- [ ] HikariCP connection pooling

#### 8.1.1 JDBC Driver Strategy — **bundle the light ones, load the rest on demand**
> Decision (see Decisions Log #9): do NOT bundle every driver. Bundle small + permissively
> licensed + common drivers; everything heavy or licensed loads on demand via a driver
> manager (the DBeaver/DataGrip model). JDBC's `ServiceLoader` SPI makes a dropped-in jar
> register automatically — no code change per driver.

**Bundled (ship in the app — small, Apache/EPL/MIT/BSD licensed):**
- [x] SQLite (`org.xerial:sqlite-jdbc`)
- [x] H2 (`com.h2database:h2`) — tested end-to-end
- [x] PostgreSQL (`org.postgresql:postgresql`)
- [x] MySQL (`com.mysql:mysql-connector-j`)
- [x] MariaDB (`org.mariadb.jdbc:mariadb-java-client`)

**On-demand (user supplies / app downloads the jar — heavy or licensed):**
- [x] Catalogued: Oracle (`ojdbc11`, OTN license), SQL Server (`mssql-jdbc`),
      IBM DB2 (`jcc`, licensed), Snowflake, ClickHouse — all in `JdbcDriverRegistry`
- [x] CockroachDB → uses the bundled PostgreSQL driver (wire-compatible)
- [ ] Redshift, BigQuery → add to catalog when needed

**Driver manager (the mechanism that makes on-demand work):**
- [x] `JdbcDriverRegistry` — catalog (class name, Maven coords, sample URL, bundled flag,
      license-ack flag); `isAvailable()` / `isDriverLoaded()`. **3/3 registry tests pass.**
- [x] `ExternalDriverLoader` — `loadFromJar()` + `downloadAndLoad()` (Maven Central →
      `~/.nexuslink/drivers/`), idempotent registration
- [x] `DriverShim` — wraps an externally-loaded driver so `DriverManager` accepts it
      (works around the child-classloader visibility rule)
- [x] "Load Driver…" UI — database picker + Browse-for-jar / Download-from-Maven menu
- [x] Per-driver license-ack flag surfaced in the UI for Oracle/DB2

### 8.2 Redis Client (separate driver — not JDBC)
- [ ] `RedisService` — Lettuce client (Cluster + Sentinel). Bundle: Lettuce is Apache-2.0, ~moderate.
- [ ] All data type explorers: String/Hash/List/Set/ZSet/Stream/Geo
- [ ] Command console with auto-complete
- [ ] Pub/Sub subscriber panel

### 8.3 MongoDB Client (separate driver — not JDBC)
- [x] `MongoService` — `org.mongodb:mongodb-driver-sync` (Apache-2.0) in its own `nexuslink-protocol-mongo` module: connect, list dbs/collections, find, aggregate, count, insertOne, updateMany, deleteMany (Extended-JSON in/out) + `MongoQueryResult`
- [x] `MongoClientView` UI — connection bar, database picker + collection list, operation selector (find/aggregate/insert/update/delete), Extended-JSON editor (Ctrl+Enter), result pane; wired into `MainWindow` (File menu + sidebar + tab opener)
- [-] Document CRUD, aggregation pipeline builder — CRUD + raw-JSON aggregation done; _visual pipeline builder TODO_
- [x] **Object explorer** (`MongoExplorer` + `ResourceExplorerView`): databases → collections → indexes tree with collStats + index definitions in the details panel
- [-] Collection stats + index manager — stats + index listing surfaced in the explorer; _create/drop index UI TODO_
- [-] Auth: SCRAM / x.509 / LDAP / Kerberos / TLS — supported via connection string (`mongodb+srv://`, TLS, SCRAM); _dedicated auth UI TODO_
- [x] Testing: `MongoServiceTest` spins up `mongo:7.0` via Testcontainers, gated behind `-DrunMongoIT=true` so the default build stays green without Docker (4 tests, skipped when the property is unset)

### 8.4 LDAP / Active Directory
- [ ] `LdapService` — UnboundID SDK, StartTLS/SSL
- [ ] Directory tree browser (DIT), entry editor (LDIF view)
- [ ] Search dialog (custom filter builder + predefined filters)

### 8.5 SSH Terminal
- [ ] `SshTerminalService` — Apache MINA SSHD
- [ ] `TerminalView` — xterm emulation (JediTerm or custom VT100 renderer)
- [ ] Multi-tab sessions, local port forwarding config

### 8.6 SNMP Browser
- [ ] `SnmpService` — SNMP4J, v1/v2c/v3
- [ ] MIB browser, OID walk, trap receiver panel

---

## PHASE 9 — MONITORING, METRICS & POLISH

### 9.1 Metrics Dashboard
- [ ] `MetricsCollector` — aggregates per-connection metrics (throughput, latency, errors)
- [ ] Real-time charts: JavaFX LineChart / AreaChart (update on 1s timer)
- [ ] P50/P95/P99 histogram
- [ ] Connection state panel (active/idle/failed counts)

### 9.2 Distributed Tracing
- [ ] W3C Trace Context injection/parsing (`traceparent`, `tracestate`)
- [ ] Jaeger/Zipkin span export
- [ ] Trace tree view in response panel

### 9.3 Team Collaboration
- [ ] Profile export as encrypted JSON bundle
- [ ] Optional cloud sync (encrypted at rest)
- [ ] RBAC: admin/developer/read-only connection profiles

### 9.4 External Secret Vaults
- [ ] HashiCorp Vault: KV v2, AppRole, AWS/Azure/GCP auth
- [ ] AWS Secrets Manager: IAM role, rotation support
- [ ] Azure Key Vault: managed identity
- [ ] CyberArk Conjur: machine identity

### 9.5 Code Generation (Global)
- [ ] `CodeGenerator` SPI — each protocol implements a generator
- [ ] Output languages: Java, Python, Go, JavaScript, cURL, PowerShell
- [ ] `CodeGenDialog.fxml` — language tabs, copy + download buttons

### 9.6 Native Packaging
- [ ] `jlink` — custom JVM runtime image (minimize size)
- [ ] `jpackage` — MSI (Windows), DMG/PKG (macOS), DEB/RPM (Linux)
- [ ] Auto-updater service

---

## KEYBOARD SHORTCUTS REFERENCE

| Action | Shortcut |
|--------|----------|
| Global search | `Ctrl+Shift+F` |
| Command palette | `Ctrl+K` |
| Help | `F1` |
| New tab | `Ctrl+T` |
| Close tab | `Ctrl+W` |
| Send request | `Ctrl+Enter` |
| Save profile | `Ctrl+S` |
| Toggle theme | `Ctrl+Shift+T` |
| Toggle log panel | `Ctrl+\`` |
| Open cert manager | `Ctrl+Shift+C` |
| Focus URL bar | `Ctrl+L` |

---

## CACHE STRATEGY REFERENCE

| Data | Cache Type | TTL | Invalidation |
|------|-----------|-----|-------------|
| DNS resolution | Caffeine (per connection) | 30s | Manual / connection close |
| TLS sessions | Caffeine | 300s | Connection close |
| Kafka topic list | Caffeine | 30s | Manual refresh |
| Schema registry schemas | Caffeine | 60s | Manual refresh |
| Consumer lag | Caffeine | 5s | Auto-poll |
| Help search results | Caffeine | session | Never (static content) |
| History (recent) | In-memory LRU | 100 entries | Eviction |
| OAuth tokens | Secure store | Until expiry | Refresh on 401 |
| JDBC schema metadata | Caffeine | 120s | Manual refresh |
| LDAP search results | Caffeine | 30s | Manual refresh |

---

## DECISIONS LOG

| # | Decision | Reason |
|---|----------|--------|
| 1 | Caffeine over Guava cache | Better performance, richer API, Java 17 compatible |
| 2 | OkHttp over Apache HC5 for REST | Simpler API, HTTP/2 support, better WS support |
| 3 | Apache MINA SSHD for SSH/SFTP | JSch is legacy; MINA is actively maintained |
| 4 | RichTextFX for code editors | Native JavaFX, no WebView overhead |
| 5 | SQLite + FTS5 for history | Zero-config embedded DB, excellent full-text search |
| 6 | Hand-rolled DI (AppContext) | Avoid module system conflicts with Spring/Guice |
| 7 | Help content in Markdown | Easy to author, version-control friendly, renderable in-app |
| 8 | Build Help System in Phase 2 | Every feature after it can register contextual help |
| 9 | **JDBC drivers: bundle light/permissive, load heavy/licensed on demand** | Bundling all drivers bloats the installer (Oracle `ojdbc11` alone is ~7MB), creates version conflicts, and drags in licensed jars (Oracle OTN, DB2) we can't legally redistribute. JDBC's `ServiceLoader` SPI means a dropped-in jar self-registers, so on-demand loading is clean. This is exactly how DBeaver/DataGrip work. Bundle: SQLite/H2/Postgres/MySQL/MariaDB (small, Apache/EPL/BSD/MIT). On-demand: Oracle/SQL Server/DB2/Snowflake/etc. Non-JDBC stores (Mongo, Redis) live in their own modules and bundle their own driver, loaded only when that protocol is used. |

---

## PROGRESS LOG

> Session notes go here. Format: `YYYY-MM-DD: <what was done>`

- 2026-06-23: Specification analyzed. TASKS.md created. Build has not started yet.
- 2026-06-24: **Session 10 — REST depth: API-key auth, code generation, settings tab.**
  - **API-key auth:** `RestRequest` gained `API_KEY` (key name/value + HEADER|QUERY placement);
    applied in `RestExecutionService` (query folded into `requestUri()`, header via `safeHeader`);
    Auth tab UI + history-replay serialization. `RestRequestTest` covers the URL/auth logic.
  - **Code generation:** `RestCodeGenerator` (cURL / Python / JavaScript / Java / PowerShell) renders
    the effective request incl. resolved auth; `CodeGenDialog` (language picker + copy) opened from a
    `</>` button on the REST bar. Tests cover curl/python output.
  - **Settings tab:** connect/read timeouts + follow-redirects wired to the request (already honored
    per-call by `RestExecutionService`).
  - **VERIFIED:** full `mvn install` clean; `mvn test` BUILD SUCCESS (RestRequestTest 6/6); GUI boots
    clean. Three commits pushed.
- 2026-06-24: **Session 9 — Credential vault wired into saved connections.**
  - `MasterPasswordDialog` (create with confirm + strength hint; unlock with retry) + `VaultSession`
    (singleton over `CredentialVault`/`VaultStore` at `~/.nexuslink/vault.json`): lazily creates/loads,
    prompts for the master password on demand, and **auto-locks after 5 min of inactivity**.
  - **Saved-connection secrets are now encrypted:** on Save, SQL passwords and credentialed Mongo
    connection strings are moved into the vault (`passwordRef` / `targetRef`); only the reference is
    written to `connections.json` (Mongo's display target is masked). On open, refs are resolved
    (unlocking the vault if needed) and pre-filled.
  - Tools menu gained **Unlock Vault… / Lock Vault**; the status bar shows a 🔒/🔓 indicator that
    toggles the lock on click.
  - **VERIFIED:** full 9-module `mvn install` clean; `mvn test` BUILD SUCCESS (vault crypto round-trip
    5/5; Mongo IT skipped); GUI boots with no exceptions or CSS warnings.
  - Follow-ups: vault REST/WS/MCP auth secrets too; `VaultBackupService` (encrypted export/import);
    per-protocol auth *flows* (OAuth2/Kerberos/SASL/mTLS).
- 2026-06-24: **Session 8 — Themes, bespoke icons, object explorer, connection manager.**
  - **Theme system:** refactored all CSS to looked-up `-nl-*` palette variables (`theme-base.css`)
    with swappable `theme-dark.css` / `theme-light.css`; `ThemeManager` (Preferences-persisted) +
    View-menu toggle + Ctrl+Shift+T; Help dialog themed too. **Fixed the menu/dropdown/context-menu
    contrast bug** (was dark-on-dark — popups now have explicit themed `.menu-item`/`.context-menu`
    rules).
  - **Bespoke SVG icons:** hand-authored a unified "node + link" glyph set (`Icons.java`, 24×24 SVG
    paths, themed via CSS `.nl-icon`). Wired into menu bar, File/AI menu items, and sidebar buttons.
  - **Object explorer:** `ResourceNode` + `ResourceExplorer` SPI (plugin-api) + lazy
    `ResourceExplorerView` (tree + details, per-type icons). Implemented `JdbcExplorer`
    (database → tables/views → columns) and `MongoExplorer` (databases → collections → indexes,
    with collStats in the details panel — added `MongoService.listIndexes/collectionStats`).
    Embedded in the SQL and Mongo views; double-click a table/collection runs a query.
  - **Saved/cached connections + multi-auth model:** `ConnectionProfile` (with a broad `AuthMethod`
    set — Basic/Bearer/API-key/TLS/mTLS/SASL/SCRAM/Kerberos/OAuth2/SSH-key/connection-string) +
    `ConnectionStore` persisting to `~/.nexuslink/connections.json`. SQL/Mongo bars gained a **Save**
    button; saved connections appear in the left tree and reopen pre-filled.
  - **Public sample catalog (deletable):** `SampleCatalog` of genuinely public test endpoints
    (httpbin, JSONPlaceholder, Postman Echo, REST Countries, Open-Meteo, public WS echoes, EBI
    RNAcentral PostgreSQL + Rfam MySQL with published read-only creds, Rebex SFTP demo, reference
    MCP server, Anthropic LLM, local Mongo/Kafka). Shown under "Samples (public)" in the left tree;
    each is hideable (right-click) and the set is restorable — so corporate users can clear them.
  - **VERIFIED:** full 9-module `mvn install` clean; **`mvn test` BUILD SUCCESS** (Mongo IT
    Docker-gated/skipped); GUI boots with no exceptions or CSS warnings; screenshot confirms the
    icons, the Saved/Samples connection tree, and readable menu contrast in light theme.
  - Follow-ups: per-protocol auth *flows* (OAuth dance, Kerberos tickets, SASL/mTLS wiring) are
    modeled but not yet implemented; Kafka/MQ/SFTP connectors still pending (catalogued as samples +
    explorer node types). Save buttons exist for SQL/Mongo; add to REST/WS next.
- 2026-06-24: **Session 7 — MongoDB client wired end-to-end (finished the half-built feature).**
  - Found the `nexuslink-protocol-mongo` backend (`MongoService`, `MongoQueryResult`) and a full
    `MongoClientView` from a prior session left **unwired**: `nexuslink-ui` had no dependency on the
    mongo module, so the whole UI module (and therefore the app) **failed to compile**, and
    `MainWindow` never referenced the view (dead code).
  - Fix: added `nexuslink-protocol-mongo` to `nexuslink-ui/pom.xml`; wired `MongoClientView` into
    `MainWindow` — File ▸ "New MongoDB Client", a "+ MongoDB Client" sidebar button, and an
    `openMongoTab()` opener ("Mongo N" tab), mirroring the SQL-client pattern exactly.
  - **VERIFIED:** full 9-module `mvn install` clean; **`mvn test` BUILD SUCCESS** (REST/WS/SQL/JDBC-
    registry/MCP/vault/history all green; Mongo IT correctly skipped — Docker-gated via
    `-DrunMongoIT=true`). App launches and stays up with no exceptions (only the benign SLF4J NOP
    warning from the driver); confirmed `mongodb-driver-sync`/`bson`/`driver-core` 5.1.4 resolve
    transitively onto the app's runtime classpath. Now 6 protocol tab types coexist
    (REST/WS/SQL/Mongo/MCP/Agent).
  - Follow-ups (left `[ ]`/`[-]` in §8.3): visual aggregation-pipeline builder, collection stats +
    index manager, dedicated auth UI (connection-string auth works today).
- 2026-06-23: **Session 6 — JDBC driver manager + bundled drivers.**
  - Bundled drivers: **H2, PostgreSQL, MySQL, MariaDB** (joining SQLite). The SQL client now
    works out of the box against all five.
  - Driver manager (`nexuslink-protocol-db`): `DriverInfo`, `JdbcDriverRegistry` (11-driver
    catalog: bundled + on-demand, with class/coords/sample-URL/license flags), `DriverShim`
    (child-classloader workaround), `ExternalDriverLoader` (load-from-jar + download-from-
    Maven-Central into `~/.nexuslink/drivers/`).
  - UI: `SqlClientView` gained a Database picker (fills URL template, marks ⬇ on-demand /
    ✓ loaded) and a "Load Driver…" menu (Browse for jar / Download from Maven).
  - **VERIFIED:** 9-module build clean; **23/23 tests pass** (added H2 end-to-end round-trip
    + 3 registry tests proving bundled drivers resolve and on-demand ones don't until loaded).
    Screenshot shows the Database picker in the SQL client.
  - Strategy + rationale recorded in §8.1.1 and Decisions Log #9.
- 2026-06-23: **Session 5 — Docs overhaul + WebSocket + JDBC SQL client.**
  - Docs: created `README.md` (overview, feature-status table, quick start), `docs/ARCHITECTURE.md` (module layout, patterns, add-a-protocol checklist); updated `RUN.md`; added an Implementation-Status banner to `NexusLink_Specification.md`.
  - **JDBC SQL client** (`nexuslink-protocol-db` enabled): `JdbcService` (connect/execute/listTables/describeTable, SELECT vs update detection, NULL rendering, row cap) + `QueryResult`. **4/4 unit tests pass** against in-memory SQLite (connect, CRUD, nulls/errors, schema introspection). UI `SqlClientView` — JDBC URL bar, schema sidebar, SQL editor (Ctrl+Enter), result grid, status. Verified live: seeded SQLite shown in a populated grid.
  - **WebSocket client** (`nexuslink-protocol-http/ws`): `WebSocketService` over JDK `java.net.http.WebSocket` (text frame reassembly, listener callbacks). UI `WebSocketView` — URL bar, connect/disconnect, timestamped message log with direction markers, send bar.
  - Both wired into `MainWindow` (File menu items, sidebar buttons, tab openers). Now 5 protocol tab types coexist (REST/WS/SQL/MCP/Agent).
  - **VERIFIED:** 9-module build clean; full suite **19/19 tests pass**; screenshot shows all 5 tabs + live SQL grid.
- 2026-06-23: **Session 4 — MCP + AI agent testing tools (new feature, beyond original spec).**
  - New module `nexuslink-protocol-ai` (Anthropic Java SDK + MCP).
  - **MCP Inspector** — full JSON-RPC 2.0 Model Context Protocol client: `JsonRpc`, `McpTransport` (interface), `HttpMcpTransport` (Streamable HTTP + SSE), `StdioMcpTransport` (subprocess), `McpClient` (initialize handshake + notifications/initialized, tools/list+call, resources/list+read, prompts/list+get), `McpTypes`. **5/5 unit tests pass** against an in-memory mock MCP server (handshake, tools, resources, prompts, JSON-RPC error surfacing).
  - **Agent/LLM Tester** — `AnthropicService` using the official Anthropic Java SDK (`com.anthropic:anthropic-java:2.34.0`), model `claude-opus-4-8` default + adaptive thinking, reads `ANTHROPIC_API_KEY`, returns text + token usage; gracefully reports when key absent.
  - UI: `McpInspectorView` (transport picker, connect, Tools/Resources/Prompts panes with call/read/get + result), `LlmTesterView` (model dropdown, system+user editors, response + usage, key-status indicator). Wired into `MainWindow` — new `AI` menu, sidebar buttons, tab openers.
  - **VERIFIED:** full build (7 modules) clean; all MCP tests green; GUI shows REST + Agent + MCP tabs side by side. Screenshot captured. Live MCP connect needs an MCP server (e.g. `npx @modelcontextprotocol/server-everything`); live LLM needs `ANTHROPIC_API_KEY`.
- 2026-06-23: **Session 1 complete.** Built:
  - Root `pom.xml` with all 9 modules and full `<dependencyManagement>` (all versions pinned)
  - All Maven module directories scaffolded
  - `nexuslink-plugin-api`: `ProtocolConnector` SPI, `PluginDescriptor`, `ConnectionConfig`, `ValidationResult`, `ConnectionResult`
  - `nexuslink-core`: `EventBus` (typed, async, weak refs), `CacheRegion` (Caffeine typed wrapper), `CacheRegistry` (all 10 standard cache regions pre-registered with correct TTLs), `AppContext` (hand-rolled DI, singleton + prototype)
  - `nexuslink-ui/help`: `HelpTopic`, `HelpIndex` (inverted index + fuzzy prefix search + highlight), `SearchResult`, `HelpService` (Caffeine-cached search, context mappings, topic loading, recently-viewed), `HelpDialog` (full 3-pane UI: index tree + rendered content + section nav, live search with debounce, F1 context-sensitive, tip rotator, smooth animations)
  - `help-dialog.css` — full dark theme for help dialog
  - Help Markdown files: `getting-started.md`, `keyboard-shortcuts.md` (stubs for all 17 topics registered)
- 2026-06-23: **Session 3 — Foundation: credential vault + persistent history.**
  - Enabled `nexuslink-security` module: `CredentialVault` (AES-256-GCM, PBKDF2-HMAC-SHA256 @200k iters, per-secret random IV, lock/unlock) + `VaultStore` (JSON persistence). **5/5 unit tests pass** (round-trip, persist/reload, wrong-password rejection, locked-access rejection, non-deterministic ciphertext).
  - `nexuslink-core`: `HistoryEntry` + `HistoryStore` (SQLite, FTS5 full-text search with LIKE fallback, Caffeine `history-recent` region, favorites, replay detail JSON). **5/5 unit tests pass** incl. persistence across reopen.
  - `nexuslink-ui`: `HistoryPanel` (searchable list, Replay + ★ favorite), wired into `MainWindow` bottom tabs (Log | History). Every REST call is recorded; `RestClientView.serializeRequest()/loadRequest()` enable one-click replay into a new tab.
  - **VERIFIED LIVE:** seeded history across 3 separate app runs → SQLite has the rows (confirmed via direct query); GUI shows `History (3)` populated with color-coded 200s + timestamps; REST response pretty-prints httpbin JSON. Screenshot captured.
  - NOTE: history DB at `~/.nexuslink/history.db`. Vault not yet wired into REST auth UI (next: store Bearer/Basic secrets as vault refs instead of plaintext in replay JSON).
- 2026-06-23: **Session 2 — Main shell + working REST client (vertical slice).**
  - Enabled `nexuslink-protocol-http` module: `RestRequest`, `RestResponse` (with per-phase `Timing`), `RestExecutionService` (JDK `java.net.http`, HTTP/2, off-thread)
  - `nexuslink-ui`: `MainWindow` shell (menu bar + global search, connection tree, workspace tabs, collapsible log panel, status bar, F1/Ctrl+T/Ctrl+Enter/Ctrl+\` accelerators), `RestClientView` (method bar, Params/Headers/Body/Auth tabs with editable tables, body JSON formatter, Basic/Bearer auth, response panel with color-coded status + timing + size + HTTP version, Body/Headers viewers)
  - `app-dark.css` + `rest-client.css` app-wide dark theme
  - Launcher now opens `MainWindow` (placeholder retired)
  - **VERIFIED LIVE:** headless smoke test hit httpbin → `200 OK` over HTTP/2 with query-param + header echo; GUI auto-send rendered a real response (`503` from httpbin's gateway, color-coded red) with timing/size/log all working. Screenshots captured.
  - Fixed init-order bug (status bar built before center) and learned: hand-rolled `--module-path` must include the main javafx-base jar (not just `-linux`) or `TableView` throws `MappingChange` CNFE — so use `mvn javafx:run` (correct module path) to run.
- 2026-06-23: **App is now runnable + verified on screen.** Added:
  - `nexuslink-ui/pom.xml` and `nexuslink-app` module (POM + `NexusLinkLauncher`)
  - Fixed parent POM: Java 17 → 21 (virtual threads need 21), removed `--enable-preview`, trimmed `<modules>` to the 4 buildable ones (protocol/security modules commented out until implemented — re-enable as built)
  - `HelpDialog.openWithSearch(query)` deep-link method + `-Dnexuslink.autohelp`/`-Dnexuslink.autosearch` demo hooks
  - `RUN.md` with build/run instructions
  - **Verified by screenshot:** launcher window + full 3-pane Help dialog both render correctly with brand theme.
  - Known quirk documented: detached/backgrounded launches make the GTK loop exit immediately; run in a foreground terminal (`mvn javafx:run`). Not an app bug.
  - **Outstanding polish:** Markdown renderer doesn't yet handle inline `**bold**`/`` `code` ``/tables/links (shows literal markers) — tracked as the `HelpRenderer` `[ ]` item in §2.2.

---

## NEXT ACTION

**Foundation + first protocols are done and verified.** Working today: shell, help system,
vault (core), history, REST, WebSocket, SQL/JDBC, **MongoDB**, MCP Inspector, AI/LLM tester.
Full `mvn test` BUILD SUCCESS (Mongo integration tests Docker-gated via `-DrunMongoIT=true`).

Highest-value next steps (pick per priority):
1. **Wire the vault into the UI** — master-password dialog + auto-lock; store REST/LLM secrets as
   vault refs instead of plaintext in history replay.
2. **MCP → Agent loop** — feed an MCP server's tools into the LLM tester so Claude can call them
   (the "agent testing" endgame). Use the Anthropic SDK tool-runner.
3. **Kafka client** (Phase 4) — needs a broker; highest-demand messaging protocol.
4. **REST depth** — OAuth 2.0 flows, code generation, more response viewers.
5. **Theming** — light theme + `ThemeManager` toggle; bundle Inter/JetBrains Mono fonts.

On resume: read this file, run `mvn -DskipTests install` then `mvn test`, then `cd nexuslink-app && mvn javafx:run`.

