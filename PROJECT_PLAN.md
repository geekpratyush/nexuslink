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
├── nexuslink-core                ← EventBus, AppContext (DI), Caffeine cache, History store
├── nexuslink-security            ← CredentialVault (AES-256-GCM), VaultStore
├── nexuslink-protocol-http       ← REST + WebSocket
├── nexuslink-protocol-ai         ← MCP Inspector + Anthropic LLM / agent tester
├── nexuslink-protocol-db         ← JDBC SQL client + on-demand driver manager
├── nexuslink-protocol-mongo      ← MongoDB client
├── nexuslink-protocol-s3         ← S3 / object storage (AWS SDK v2)
├── nexuslink-protocol-kafka      ← Kafka (admin/producer/consumer)
├── nexuslink-protocol-redis      ← Redis (Lettuce)
├── nexuslink-protocol-azure      ← Azure Blob Storage
├── nexuslink-protocol-gcs        ← Google Cloud Storage
├── nexuslink-protocol-grpc       ← gRPC (dynamic, reflection-based)
├── nexuslink-protocol-sftp       ← SFTP (Apache MINA SSHD)
├── nexuslink-ui                  ← shell (MainWindow), all protocol views, Help system
└── nexuslink-app                 ← JavaFX entry point (the ONLY runnable module)
    + planned: protocol-messaging, protocol-file, protocol-enterprise
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
| **History** | core | SQLite + FTS5 full-text search, favorites, one-click replay. |
| **REST client** | protocol-http | HTTP/2, params/headers/body/auth tabs, color-coded status, timing, JSON pretty-print. |
| **WebSocket client** | protocol-http | Connect/disconnect, timestamped message log, send bar. |
| **SQL / JDBC client** | protocol-db | 5 bundled drivers (SQLite/H2/Postgres/MySQL/MariaDB) + on-demand driver manager (Oracle/DB2/…). |
| **MongoDB client** | protocol-mongo | Connect, db/collection browse, find/aggregate/insert/update/delete via Extended-JSON. |
| **MCP Inspector** | protocol-ai | Full JSON-RPC 2.0 Model Context Protocol client (HTTP/SSE + stdio transports). |
| **AI / LLM tester** | protocol-ai | Anthropic Java SDK, `claude-opus-4-8` default with adaptive thinking. |

Six protocol tab types coexist in the workspace: **REST · WS · SQL · Mongo · MCP · Agent.**

---

## 5. Phase roadmap

| Phase | Theme | Status |
|-------|-------|--------|
| **0** | Project scaffold (Maven, JPMS, core infra) | ✅ Substantially done |
| **1** | Foundation: vault, cert manager, profiles, env vars, history | 🟡 Vault, history, **connection profiles + store + public samples** done; cert manager / env vars / profile encryption pending |
| **2** | Help system (built early to guide everything) | ✅ Engine + dialog done; more topic authoring + richer renderer pending |
| **3** | HTTP core: REST, WebSocket, SSE | 🟡 REST + WS done; SSE + REST depth (OAuth, code-gen, more viewers) pending |
| **4** | Kafka client (producer/consumer/admin/schema registry/monitoring) | ⬜ Not started (needs a broker) |
| **5** | Enterprise messaging (JMS, IBM MQ, Solace, MQTT, RabbitMQ, cloud) | ⬜ Not started |
| **6** | Advanced HTTP (gRPC, GraphQL) | ⬜ Not started |
| **7** | File transfer (SFTP/SCP, FTP/FTPS, S3/Azure/GCS) | ⬜ Not started |
| **8** | Databases & enterprise (JDBC, **Mongo**, Redis, LDAP, SSH, SNMP) | 🟡 JDBC + Mongo done; Redis/LDAP/SSH/SNMP pending |
| **9** | Monitoring, metrics, tracing, secret vaults, code-gen, native packaging | ⬜ Not started |

Legend: ✅ done · 🟡 in progress · ⬜ not started

---

## 6. Highest-value next steps

1. **Wire the vault into the UI** — master-password dialog + auto-lock; store REST/LLM secrets
   as vault references instead of plaintext in history replay.
2. **MCP → Agent loop** — feed an MCP server's tools into the LLM tester so the model can call
   them (the "agent testing" endgame), using the Anthropic SDK tool-runner.
3. **Kafka client (Phase 4)** — highest-demand messaging protocol.
4. **REST depth** — OAuth 2.0 flows, code generation, richer response viewers.
5. **Auth flows** — implement the modeled `AuthMethod`s end-to-end per protocol (OAuth2 dance, Kerberos, SASL/SCRAM, mTLS), and store secrets as vault refs.
6. ~~Theming — light theme + toggle~~ ✅ done (dark/light palette system, Ctrl+Shift+T). _Remaining: bundle Inter / JetBrains Mono fonts; system auto-detect._

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

_Author: Pratyush Ranjan Mishra · Last updated: 2026-06-24 · maintained alongside `TASKS.md` (the living build log)._
