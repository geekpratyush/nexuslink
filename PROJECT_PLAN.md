# NexusLink — Project Planner

> A single-page map of **what NexusLink is**, **how it's built**, and **where it stands today**.
> For the granular, checkbox-level build log see [`TASKS.md`](./TASKS.md); for the full product
> spec see [`NexusLink_Specification.md`](./NexusLink_Specification.md).

---

## 1. What is NexusLink?

**NexusLink is a universal protocol workbench** — a single JavaFX desktop application for
connecting to, testing, and inspecting the protocols and data stores an engineer touches
day to day. Think *Postman + DBeaver + a Kafka tool + an MCP/LLM tester*, unified behind one
themed, keyboard-driven, searchable workspace where **you never have to leave the app to
understand a feature** (an embedded, indexed Help system is a first-class citizen).

**Core idea:** every protocol is a *plugin* exposing a connector (backend) + a view (UI tab).
The shell, vault, history, help, and caching are shared infrastructure that every protocol
reuses — so adding a protocol is a small, well-defined unit of work.

---

## 2. Goals & Principles

| Principle | What it means in practice |
|-----------|---------------------------|
| **One app, many protocols** | REST, WebSocket, SQL, MongoDB, Kafka, MQTT, gRPC, SFTP, … all in one workspace with consistent UX. |
| **Pluggable by design** | `ProtocolConnector` SPI + per-protocol Maven module. New protocol = new module + a view wired into the shell. |
| **Secure by default** | Secrets live in an AES-256-GCM vault (PBKDF2, 200k iters), never plaintext on disk. |
| **Help is built-in** | Searchable, context-sensitive, `F1`-anywhere — authored alongside each feature. |
| **No heavy frameworks** | Hand-rolled DI (`AppContext`), JDK HTTP client, Caffeine cache — minimal deps, JPMS-friendly. |
| **Verify on real targets** | Features are validated against live endpoints / containers, not just unit mocks. |

---

## 3. Architecture at a glance

- **Language / runtime:** Java 21 (LTS), virtual threads.
- **UI:** JavaFX 21 + CSS, **MVVM** (View → ViewModel → Service), mostly programmatic (not FXML).
- **Build:** Maven 3.9+ multi-module reactor; JPMS modules.
- **Persistence:** SQLite + FTS5 (history), AES-256-GCM encrypted JSON (vault / profiles).
- **Cache:** Caffeine, with 10 pre-registered regions (DNS, TLS, schema, lag, help, …).
- **Pattern for "add a protocol":** new `nexuslink-protocol-*` module (connector) → a
  `*View` in `nexuslink-ui` → wire into `MainWindow` (menu + sidebar + tab opener).

### Module map

```
nexuslink-parent (pom)            ← aggregator + dependencyManagement (all versions pinned)
├── nexuslink-plugin-api          ← ProtocolConnector SPI, ConnectionConfig, result records
├── nexuslink-core                ← EventBus, AppContext (DI), Caffeine cache, History store,
│                                   EnvironmentService (${VAR} / .env / secret masking)
├── nexuslink-security            ← CredentialVault (AES-256-GCM), VaultStore, certificate manager
├── nexuslink-protocol-http       ← REST + WebSocket
├── nexuslink-protocol-ai         ← MCP Inspector + Anthropic LLM / agent tester
├── nexuslink-protocol-db         ← JDBC SQL client + on-demand driver manager
├── nexuslink-protocol-mongo      ← MongoDB client
├── nexuslink-protocol-s3         ← S3 / object storage (AWS SDK v2)
├── nexuslink-protocol-kafka      ← Kafka (admin/producer/consumer)
├── nexuslink-protocol-mqtt       ← MQTT (Eclipse Paho; connect/subscribe/publish)
├── nexuslink-protocol-rabbitmq   ← RabbitMQ (AMQP 0.9.1; declare/publish/consume)
├── nexuslink-protocol-redis      ← Redis (Lettuce)
├── nexuslink-protocol-azure      ← Azure Blob Storage
├── nexuslink-protocol-gcs        ← Google Cloud Storage
├── nexuslink-protocol-grpc       ← gRPC (dynamic, reflection-based)
├── nexuslink-protocol-sftp       ← SFTP (Apache MINA SSHD)
├── nexuslink-protocol-ftp        ← FTP / FTPS (Apache Commons Net)
├── nexuslink-ui                  ← shell (MainWindow), all protocol views, Help system
└── nexuslink-app                 ← JavaFX entry point (the ONLY runnable module)
    + planned: protocol-messaging (JMS), protocol-file, protocol-enterprise (IBM MQ/Solace)
```

> **Why you start the app from `nexuslink-app`:** the parent POM is a pure aggregator and the
> other modules are libraries — only `nexuslink-app` defines a `mainClass`
> (`com.nexuslink.app.NexusLinkLauncher`). Run it with `mvn -pl nexuslink-app -am javafx:run`
> from the root, or `cd nexuslink-app && mvn javafx:run`. See [`RUN.md`](./RUN.md).

---

## 4. Current status — what works **today**

Built, wired into the shell, and verified (full `mvn test` is green):

| Capability | Module | Notes |
|------------|--------|-------|
| **Workspace shell** | ui | Menu + global search, connection tree, tabbed workspace, collapsible log panel, status bar, accelerators. |
| **Theming** | ui | Dark + light themes via looked-up `-nl-*` palette variables; `ThemeManager` toggle (Ctrl+Shift+T), persisted. |
| **Bespoke icons** | ui | Original "node + link" SVG icon set, themed, used across menus, sidebar, and the object explorer. |
| **Connection manager** | core + ui | Saved connections persisted to `~/.nexuslink/connections.json`; bundled **public sample** endpoints (deletable); flexible multi-method auth model. |
| **Object explorer** | plugin-api + ui | Lazy resource tree + details for SQL (db→tables→columns) and Mongo (db→collections→indexes/stats). |
| **Markdown + Mermaid** | ui | `MarkdownView` (WebView) renders GFM Markdown + Mermaid diagrams; powers Help content and DB **ER diagrams**. |
| **Help system** | ui | 3-pane searchable dialog (Markdown-rendered), live debounced search, context-sensitive `F1`, tips. |
| **Credential vault** | security + ui | AES-256-GCM + PBKDF2 (200k); master-password dialog, auto-lock, status-bar lock toggle. Saved-connection secrets stored as vault refs (no plaintext). |
| **Certificate manager** | security + ui | Generate self-signed RSA/ECDSA, import/export PEM/DER, persist to PKCS12/JKS keystore, colour-coded validity + 30/7/1-day **expiry watchdog**. |
| **Environment variables** | core + ui | Named `${VAR}` environments (dev/staging/prod) + active selection; resolution active env → `.env` → system env; `${VAR:-default}`/nested/escape; secrets masked in UI + scrubbed from logs. **Resolved at send/connect time in every protocol view.** |
| **History** | core | SQLite + FTS5 full-text search, favorites, one-click replay. |
| **REST client** | protocol-http | HTTP/2, params/headers/body/auth tabs, color-coded status, timing, JSON pretty-print. |
| **WebSocket client** | protocol-http | Connect/disconnect, timestamped message log, send bar. |
| **SQL / JDBC client** | protocol-db | 5 bundled drivers (SQLite/H2/Postgres/MySQL/MariaDB) + on-demand driver manager (Oracle/DB2/…). |
| **MongoDB client** | protocol-mongo | find/SQL/aggregate/explain/CRUD, inferred schema diagram, Compass-style JSON/Table/Schema views, JSON/CSV export, query history. |
| **Redis client** | protocol-redis | Lettuce; key browser with typed value rendering + command console. _(Needs a live server for E2E.)_ |
| **Kafka client** | protocol-kafka | Admin topic explorer, produce, consume. _(First cut; needs a broker for E2E.)_ |
| **MQTT client** | protocol-mqtt | Eclipse Paho; connect, subscribe to topic filters, publish. **Verified live (HiveMQ public broker).** _(First cut.)_ |
| **RabbitMQ client** | protocol-rabbitmq | Official `amqp-client` (AMQP 0.9.1); declare exchange/queue/binding, publish, consume into a live log; `${VAR}` in every field; pure `factoryFor` seam **7/7 unit tests**. _(First cut; needs a broker for E2E.)_ |
| **SSE client** | protocol-http | Live `text/event-stream` log with event-type filter. **Verified live (Wikimedia firehose).** |
| **GraphQL client** | protocol-http | Query/variables editor + one-click introspection. **Verified live.** |
| **gRPC client** | protocol-grpc | Reflection-based service/method discovery + unary invoke (JSON ↔ DynamicMessage). **Verified live (grpcb.in).** |
| **SFTP / FTP / FTPS** | protocol-sftp / -ftp | Remote directory tree browse + file read. **Verified live (test.rebex.net).** |
| **S3 / Azure / GCS** | protocol-s3 / -azure / -gcs | Bucket→object browsers behind one shared explorer view. **S3 verified live (MinIO).** |
| **MCP Inspector** | protocol-ai | Full JSON-RPC 2.0 Model Context Protocol client (HTTP/SSE + stdio); optional **Bearer-token auth**. |
| **AI / LLM tester** | protocol-ai | Anthropic Java SDK, `claude-opus-4-8` default with adaptive thinking. |

Many protocol tab types coexist in the workspace: **REST · WS · SSE · GraphQL · gRPC · SQL ·
Mongo · Redis · Kafka · MQTT · RabbitMQ · SFTP/FTP · S3/Azure/GCS · MCP · Agent.**

---

## 5. Phase roadmap

| Phase | Theme | Status |
|-------|-------|--------|
| **0** | Project scaffold (Maven, JPMS, core infra) | ✅ Substantially done |
| **1** | Foundation: vault, cert manager, profiles, env vars, history | ✅ Vault (+UI/auto-lock), history, profiles + store + public samples, **certificate manager (+ expiry watchdog)**, **environment-variable system** done; remaining: `ProfileValidator`, cert export/import polish |
| **2** | Help system (built early to guide everything) | ✅ Engine + dialog + all 17 topics + Markdown/Mermaid renderer done |
| **3** | HTTP core: REST, WebSocket, SSE | 🟡 REST (+OAuth2 client-creds, code-gen), WS, **SSE** done; REST depth (more auth flows, viewers) pending |
| **4** | Kafka client (producer/consumer/admin/schema registry/monitoring) | 🟡 First cut (admin/produce/consume + explorer) done; schema registry/metrics/lag pending — **needs a broker for E2E** |
| **5** | Enterprise messaging (JMS, IBM MQ, Solace, MQTT, RabbitMQ, cloud) | 🟡 **MQTT** (verified live) + **RabbitMQ** (declare/publish/consume, first cut) done; RabbitMQ management REST + JMS/IBM MQ/Solace/cloud pending |
| **6** | Advanced HTTP (gRPC, GraphQL) | 🟡 **gRPC** (reflection, unary) + **GraphQL** (query/introspection) done; streaming/subscriptions pending |
| **7** | File transfer (SFTP/SCP, FTP/FTPS, S3/Azure/GCS) | 🟡 **SFTP, FTP/FTPS, S3, Azure Blob, GCS** browse/read done; local pane + transfer queue + uploads pending |
| **8** | Databases & enterprise (JDBC, **Mongo**, Redis, LDAP, SSH, SNMP) | 🟡 JDBC + Mongo (power features) + **Redis** done; LDAP/SSH/SNMP pending |
| **9** | Monitoring, metrics, tracing, secret vaults, code-gen, native packaging | ⬜ Not started |

Legend: ✅ done · 🟡 in progress · ⬜ not started

**Overall: ~48% of tracked tasks complete** (127 done · 28 in-progress · 98 not started; see `TASKS.md`). **Phase-1 foundations are complete.**

---

## 6. Highest-value next steps

1. **MCP → Agent loop** — feed an MCP server's tools into the LLM tester so the model can call
   them (the "agent testing" endgame), using the Anthropic SDK tool-runner. _(MCP now supports
   Bearer-token auth; next: vault the token + an OAuth/PKCE flow.)_
2. **Enterprise messaging (Phase 5)** — RabbitMQ depth next (management REST API, publisher
   confirms, manual ack/nack/requeue, DLX viewer), then JMS / cloud messaging. _(MQTT + RabbitMQ
   first cuts are done.)_
3. **REST depth** — remaining OAuth 2.0 flows (auth-code/PKCE), Digest/NTLM/AWS-SigV4 auth,
   richer response viewers (cookies, waterfall timeline, test assertions).
4. **Auth flows** — implement the modeled `AuthMethod`s end-to-end per protocol (OAuth2 dance,
   Kerberos, SASL/SCRAM, mTLS), and store secrets as vault refs.
5. **Kafka depth** — schema registry, consumer-lag monitor, metrics (the first cut exists).

_Done since this list was first written:_ ✅ vault UI + auto-lock · ✅ SSE · ✅ GraphQL · ✅ gRPC ·
✅ Kafka first cut · ✅ Redis · ✅ SFTP/FTP · ✅ S3/Azure/GCS · ✅ Mongo power features ·
✅ dark/light theming · ✅ MCP Bearer auth · ✅ **MQTT first cut** · ✅ **RabbitMQ first cut** ·
✅ **certificate manager (+ expiry watchdog)** ·
✅ **environment-variable system (+ `${VAR}` live in every protocol view)**.
_(Remaining theming: bundle Inter / JetBrains Mono fonts; system theme auto-detect.)_

---

## 7. How to build & run

```bash
# from the repo root
mvn -DskipTests install          # build all modules
mvn test                         # run the suite (Mongo integration tests are Docker-gated:
                                 #   add -DrunMongoIT=true with Docker running to exercise them)
mvn -pl nexuslink-app -am javafx:run   # launch the desktop app (needs a graphical display)
```

> NexusLink is a **GUI app** — it will not run headless. See [`RUN.md`](./RUN.md) for direct-`java`
> launch, demo flags, and a known dev-environment TTY quirk.

---

## 8. Key decisions (abridged)

- **Hand-rolled DI** over Spring/Guice — avoids JPMS module-system conflicts.
- **Caffeine** over Guava cache — better performance + API on Java 21.
- **SQLite + FTS5** for history — zero-config embedded DB with excellent full-text search.
- **Build the Help system in Phase 2** — every later feature registers contextual help into it.
- **JDBC drivers: bundle the light/permissive ones, load heavy/licensed ones on demand** —
  keeps the installer small and avoids redistributing licensed jars (the DBeaver/DataGrip model).

_Full rationale for each lives in the Decisions Log of [`TASKS.md`](./TASKS.md)._

---

_Author: Pratyush Ranjan Mishra · Last updated: 2026-06-26 · maintained alongside `TASKS.md` (the living build log)._
