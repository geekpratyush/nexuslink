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
| Progress | **~65%** — 197 done · 45 in-progress · 61 open of 303 (~73% weighted). Phases 1, 2 & (essentially) 4 complete; `mvn test` green across all 24 modules. Docker `test-env/` live-verifies 15 protocol families |

---

## ⏭ CLOSE-OUT PLAN — resume 2026-07-05 (read this first)

**State at shutdown (2026-07-04):** clean tree, all pushed to `origin/main` @ `dc38b23`, `mvn test` green
across 24 modules. Docker works here (v29 / compose v5). Two containers were left running (`artemis`,
`localstack`) — a machine restart stops them; bring the stack back with
`docker compose -f test-env/docker-compose.yml up -d`.

**Can everything close in one day? Honestly: ~90% yes, 100% no.** ~106 items remain (61 open + 45 partial).
The Docker-simulate pattern (proven this session for SQS/SNS + JMS) makes most of the "infra-gated" set
buildable and live-verifiable in a focused day. A handful genuinely can't be *fully* closed offline and
should be marked **won't-close-today** with the reason (see Wave E). Suggested one-day order:

**Wave A — Docker-verified brokers (highest value; ~pattern = 20–30 min each):**
1. **MQTT v5** (§5.4) — swap Paho v3→v5 client, add v5 props; Mosquitto already in stack.
2. **HashiCorp Vault** (§9.4) — `hashicorp/vault` dev image; KV v2 read/write.
3. **AWS Secrets Manager** (§9.4) — LocalStack (add `secretsmanager` to SERVICES); reuse AWS SDK v2.
4. **Solace** (§5.3) — `solace/solace-pubsub-standard` + JCSMP client.
5. **CyberArk Conjur** (§9.4) — `cyberark/conjur` OSS image (dockerable, unlike Key Vault).
6. **IBM MQ** (§5.2) — `icr.io/ibm-messaging/mq` dev image (heavy ~2GB, needs `LICENSE=accept`).
7. **SSH terminal service** (§8.5) — MINA SSHD client vs `linuxserver/openssh-server` (service+exec; the
   VT100 *UI* is a separate big piece — do the service, defer the full terminal renderer).
8. **Google Pub/Sub** (§5.6) — `gcr.io/google.com/cloudsdktool/...` pubsub emulator.

**Wave B — offline pure/UI cores (fast, testable):** help-system components (`HelpButton`, tooltip-plus,
`ErrorHelpLink`, empty-state, onboarding), `ProfileEditorDialog`, `PropertiesPanel`, connection-state
panel, Redis Pub/Sub panel, HikariCP pooling, SCP mode, drop-onto-folder-row, module-info files,
`.gitignore`/README skeleton, fonts.

**Wave C — UI panels for the new backend modules:** SQS/SNS view, JMS view, Vault/Secrets view (services
already done + live-verified this session — just wire the JavaFX panels + File-menu entries).

**Wave D — charts (JavaFX `LineChart`/heatmap; no headless test — verify by launching):** Kafka lag chart,
throughput chart, lag heatmap, per-endpoint metrics breakdown, distributed-trace tree view.

**Wave E — WON'T fully close offline (mark + move on, don't fake):** Azure Key Vault (no local emulator —
needs a real Azure account/managed identity); cloud sync + RBAC (§9.3, need a backend service); auto-updater
(§9.6, needs a release server — can build the client check only); native per-OS installers (need per-OS
tooling/signing). These are the realistic residual after a full close-out day.

**How to work each item:** pure/JavaFX-free core + unit tests → thin UI wiring (gate on `mvn -pl nexuslink-ui
-am compile` + full `mvn test`; no TestFX harness) → for a broker, a `*LiveIT` gated on `-Dnexuslink.it=true`
+ a compose service, run it, confirm PASS. Commit+push each; keep `origin/main` in sync; no AI attribution.

---

## LIVE INTEGRATION TESTING — local Docker stack (`test-env/`)

An all-open-source Docker Compose stack (`test-env/docker-compose.yml`) runs one real server per
protocol module so the clients can be exercised end-to-end on a laptop — no cloud accounts, no
licences. Each module ships a `*LiveIT` gated on `-Dnexuslink.it=true`, so the default `mvn test`
stays green without the stack. See `test-env/README.md`; one-shot runner: `test-env/run-live-its.sh`.

**End-to-end verified against the local stack** (13 protocol families):

| Module | LiveIT | Server (open-source image) |
|---|---|---|
| protocol-db | `JdbcLiveIT` | PostgreSQL + MariaDB (DDL/insert/select/listTables) |
| protocol-redis | `RedisLiveIT` | Redis (set/get/scan/type) |
| protocol-kafka | `KafkaLiveIT` | Kafka KRaft (produce → consume round-trip) |
| protocol-rabbitmq | `RabbitMqLiveIT` | RabbitMQ (publish → consume) |
| protocol-mqtt | `MqttLiveIT` | Mosquitto (subscribe → publish) |
| protocol-ldap | `LdapLiveIT` | OpenLDAP (bind, naming contexts, search) |
| protocol-snmp | `SnmpLiveIT` | net-snmp (GET + WALK) |
| protocol-s3 | `S3LiveIT` | LocalStack S3 (list buckets/objects, get) |
| protocol-sqs | `SqsSnsLiveIT` | LocalStack SQS+SNS (send/receive/delete, FIFO, publish) |
| protocol-jms | `JmsLiveIT` | ActiveMQ Artemis (send/receive + non-consuming browse) |
| protocol-azure | `AzureLiveIT` | Azurite (list containers/blobs) |
| protocol-gcs | `GcsLiveIT` | fake-gcs-server (emulator-aware `GcsService`) |
| protocol-sftp | `SftpLiveIT` | atmoz/sftp (upload/list/read/delete) |
| protocol-ftp | `FtpLiveIT` | vsftpd (upload/list/read/delete) |
| protocol-http | `RestLiveIT` | go-httpbin (GET/POST/basic-auth) |
| protocol-mongo | `MongoServiceTest` | MongoDB via Testcontainers (`-DrunMongoIT=true`) |

---

## PHASE 0 — PROJECT SCAFFOLD ✦ START HERE

**Goal:** Maven multi-module project, JPMS modules, base infrastructure

### 0.1 Maven Project Structure
- [x] Create root `pom.xml` with all dependency versions in `<dependencyManagement>`
- [x] Create module POMs: `core`, `ui`, `security`, `protocol-http`, `protocol-messaging`, `protocol-file`, `protocol-db`, `protocol-enterprise`, `plugin-api`
- [x] Configure packaging — `fatjar` (uber JAR) + `jpackage` (native app-image) profiles in `nexuslink-app` (see §9.6 + PACKAGING.md)
- [ ] Add `.gitignore`, `README.md` skeleton

### 0.2 Core Module (`nexuslink.core`)
- [ ] `module-info.java` for `nexuslink.core`
- [x] `EventBus` — lightweight pub/sub (typed events, weak listeners)
- [x] `AppContext` — singleton DI container (no Spring; hand-rolled)
- [x] `CaffeineCache` wrapper (`CacheRegion`) — typed, configurable TTL per cache region
- [x] `CacheRegistry` — all 10 standard cache regions pre-registered
- [x] `ApplicationConfig` — typed get/set (String/int/boolean/double + defaults) over `java.util.prefs.Preferences` + a portable JSON overlay at `~/.nexuslink/settings.json` (load on construct, save on change); tolerates missing/malformed file → defaults; injectable `Path` for tests. 7 tests
- [-] `ThemeManager` — dark/light toggle + persistence done (`nexuslink-ui/theme`); _system auto-detect TODO_
- [x] `SettingsService` — app-facing layer over `ApplicationConfig`: named typed prefs (theme, connect/read timeouts, last-used dir, telemetry opt-in) with defaults + lightweight `Consumer<String>` change listeners. 5 tests. Wired into a **Preferences dialog** (Tools ▸ Preferences…, ⌘/Ctrl+,): theme (live via `ThemeManager`, reverts on Cancel) + default REST connect/read timeouts; `Settings` holder (lazy `AppContext` singleton, mirrors `Env`/`Metrics`) seeds new REST tabs from the saved timeouts

### 0.3 Plugin API Module (`nexuslink.plugin.api`)
- [x] `ProtocolConnector` SPI interface
- [ ] `ProtocolView` SPI (returns JavaFX Node)
- [x] `PluginDescriptor` metadata record
- [x] `ConnectionConfig` — protocol-agnostic config bag with vault refs
- [x] `ValidationResult`, `ConnectionResult` records
- [x] `ExtensionRegistry` — discovers and loads plugins via ServiceLoader — `ServiceLoader.load(ProtocolConnector)`
      indexed by `protocolId` with `find`/`contains`/`all`/`descriptors`/`ids`; discovery split from indexing via
      `fromProviders(Iterable)` (testable seam), skips null/blank ids, first-wins on duplicate id (collisions in
      `duplicateIds()`). 7 tests (added JUnit test-dep to the plugin-api pom).

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
- [x] `VaultBackupService` — encrypted, versioned backup export/import over `CredentialVault` crypto:
      export re-encrypts under a fresh backup passphrase (independent of the master), versioned header
      (`format`/`version`/`createdAt`); import GCM-tag-verifies (wrong pass or tamper → dedicated checked
      `VaultBackupException`, never a partial vault); `restoreInto` merges entries honoring `overwriteExisting`. 10 tests
- [x] Unit tests for encryption round-trip (5/5 pass)
- [x] **Wire vault into saved connections** — SQL passwords & credentialed Mongo URIs are stored as vault refs (`passwordRef`/`targetRef`), never plaintext in `connections.json`; resolved on open. _REST AuthTab vaulting still TODO._

### 1.2 Certificate Manager
- [x] `CertificateStore` — JKS/**PKCS12** keystore storage (load/save/list/import/export/delete,
      key + trusted entries, get key/chain), password-protected (`nexuslink-security/cert`)
- [x] `CertificateParser` — X.509 field extraction (subject, issuer, serial, validity, SAN,
      key algo+size, sig algo, CA flag, SHA-256 fingerprint) via the JDK `CertificateFactory`;
      `CertificateInfo` carries a VALID/EXPIRING_SOON/EXPIRED/NOT_YET_VALID status
- [x] `CertificateGenerator` — self-signed **RSA (2048/4096) / ECDSA (P-256/P-384)** with
      configurable validity + SAN (DNS/IP), BouncyCastle; PEM export of cert or key; **PKCS#10 CSR
      generation** (`generateCsr` → CSR PEM + key, SANs in a requested-extensions attribute)
- [x] `CertificateImporter` — PEM/DER **file import** + **PKCS12/JKS bundle import** (every alias →
      cert chain + private key, `typeForFileName` autodetect); wired to **Import Bundle…**; _drag-and-drop TODO_
- [x] **PEM parser** — pure `PemParser` (RFC 7468, `java.util.Base64` only): `parseAll`/`first`/`isPem` over
      one-or-many `-----BEGIN <LABEL>-----` blocks → `PemBlock(label, der[])` with a `type()` classifier
      (CERTIFICATE/PRIVATE_KEY/PUBLIC_KEY/CRL/CSR/OTHER); tolerates CRLF/LF, explanatory text, and full chains;
      `PemException` on label mismatch/truncation/bad base64. 14 tests.
- [x] `CertificateExporter` — **PEM / DER / PKCS#12-with-password** export (key+chain or cert-only
      trust store); wired to the **Export…** format chooser + **Generate CSR…**. **7/7 round-trip tests**
- [x] **Certificate Bundle Builder** (`CertificateBundleDialog`) — guided: pick certs, order leaf→root,
      pick a format (full-chain PEM / PKCS#12-with-key / CA trust bundle PEM / CA trust store PKCS#12)
      with live guidance, Build & Save. Reachable from the cert manager **Build Bundle…** button
- [x] **TLS / mTLS context** (`security/tls`: `TlsConfig` + `TlsContextFactory`) — build an `SSLContext`
      from a CA trust store and/or a client key store (mutual TLS), or trust-all; autodetects JKS/PKCS12.
      Wired into the **REST** client (Settings ▸ TLS/mTLS: trust-store/key-store browse + passwords +
      trust-all), applied in `RestExecutionService`. **6/6 tests** incl. real loopback TLS + mTLS handshakes
- [x] `ExpirationWatchdog` — clock-injectable, side-effect-free `scan()` fires once per 30/7/1-day
      threshold crossing (escalating, never repeating) + once on expiry; daemon `start(interval)` for
      background scanning; listeners + `aliasesNeedingAttention()`. Wired into `CertificateManagerView`
      (status-bar expiry summary, colour-coded, alerts logged). **6/6 pass** (`ExpirationWatchdogTest`)
- [x] `CertificateManagerView` — list + detail panel + generate/import/export/delete +
      save/open keystore; wired into **Tools ▸ Certificate Manager…** (programmatic, not FXML)
- [x] Certificate list with status icons (valid=green, warning=amber, expired=red)
- [x] Unit tests: generate (RSA+EC) → parse round-trip, status window, PEM round-trip,
      keystore persist/reload of key + trusted entries (**5/5 pass**, `CertificateManagerTest`)

### 1.3 Connection Profile Manager
- [x] `ConnectionProfile` model — name, protocol, target, username, `AuthMethod` + auth/property maps, sample flag (`nexuslink-core/connection`)
- [-] `ProfileRepository` — `ConnectionStore` persists saved profiles + hidden-sample ids to `~/.nexuslink/connections.json`, CRUD; _encryption / secret-vault refs TODO_
- [-] `ProfileImportExport` — encrypted JSON bundle, team share link — encrypted bundle done: `ProfileImportExport`
      serialises a `List<ConnectionProfile>` to JSON and encrypts it with AES-256-GCM under a PBKDF2 (200k,
      SHA-256) key from a passphrase (fresh random salt+IV per export); `importBundle` verifies format/version
      then GCM-decrypts, so a wrong passphrase or any tampering throws `ProfileBundleException` rather than
      yielding partial data. Self-contained (`javax.crypto` + Jackson). 8 tests. **UI wired:** Export…/Import…
      buttons on the `ConnectionsPanel` (FileChooser + masked passphrase dialog) export the Saved connections
      and merge imported ones back via `ConnectionStore`. _(team share-link TODO.)_
- [-] `ConnectionTreeView` — `ConnectionsPanel`: Saved + Samples groups, protocol icons, open/delete/hide; _folders, tags, color dots, drag-to-reorder TODO_
- [x] **Bundled public sample catalog** (`SampleCatalog`) — deletable/hideable public test endpoints (REST/WS/SQL/Mongo/MCP/LLM + SFTP/Kafka placeholders)
- [ ] `ProfileEditorDialog` — generic fields + protocol-specific section (pluggable)
- [x] `ProfileValidator` — per-protocol pre-save validation (name, target shape per protocol, auth-method
      required fields incl. raw-or-vaulted secret refs); blocks `saveConnection` with an Alert; **13/13 tests**

### 1.4 Environment Variable System
- [x] `EnvironmentService` — named environments (dev/staging/prod) + active selection, persisted to
      `~/.nexuslink/environments.json`; resolves `${VAR}` with precedence active env → `.env` file
      (`~/.nexuslink/.env`) → system env; `interpolate`/`interpolateAll`/`resolver`/`masker` (core)
- [x] `VariableInterpolator` — `${VAR}` + `${VAR:-default}` + `$$` escape + nested refs with cycle
      guard; unknown names left literal so the user sees what didn't resolve (core, 7/7 tests)
- [x] `EnvEditorDialog` → `EnvironmentManagerView` tab — per-environment variable table (name/value/
      secret), New/Delete/Set-Active, reveal-secrets toggle; **Tools ▸ Environments…** (nexuslink-ui)
- [x] `SecretMaskingFilter` — `looksSecret` name heuristic (auto-flags secrets), `scrub` removes
      secret values from logs/rendered requests (longest-first), `maskValue` for UI fields (core)
- [x] _Adoption_: `${VAR}` resolved at send/connect time across **every protocol view** — REST
      (URL/params/headers/body/auth, via `RestRequest.interpolated()`; secrets scrubbed from the log),
      WebSocket, SSE, GraphQL, gRPC, SQL/JDBC, MongoDB, Redis, Kafka (bootstrap/SASL/topics),
      MQTT (broker/creds/topics/payload), SFTP, FTP, S3, Azure, GCS, MCP (target/token/tool+prompt
      args), and the LLM tester (system + user prompt) — all via the shared `ui.env.Env` helper.

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
- [x] **All 17 registered help topics authored** (Markdown) — fixed the "Help file missing" errors:
  - [x] `getting-started`, `keyboard-shortcuts`, `rest-client`, `code-generation`, `security`,
        `databases`, `kafka-client`, `grpc`, `graphql`, `sftp`, `troubleshooting`, `plugins`
  - [x] Roadmap-protocol topics written as accurate "on the roadmap" notes: `mqtt`, `ldap`, `snmp`,
        `certificate-manager`, `environment-vars`

### 2.2 Help Engine
- [x] `HelpIndex` — in-memory inverted index with multi-term AND search + prefix fuzzy matching
- [x] `HelpService` — Caffeine-cached search (24h TTL), context mappings (19 UI components), recently viewed, content loader
- [x] `HelpRenderer` — `MarkdownView` (WebView + commonmark, GFM tables, themed CSS, **Mermaid** diagrams) replaces the line-based renderer; headings/bold/code/lists/tables/links render properly. Verified by screenshot.
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
  - [-] OAuth 2.0 — **client-credentials** (`OAuth2TokenClient`, cached/auto-refresh, applied in `RestExecutionService`)
    + **authorization-code with PKCE** (`OAuth2AuthorizationCode`: S256 challenge per RFC 7636, auth-URL build,
    redirect parse, token exchange; interactive `OAuth2AuthCodeDialog` opens the browser → applies a Bearer token;
    **8/8 tests** incl. the RFC 7636 known-answer vector); _implicit / password / device-code flows TODO_
  - [-] **AWS Signature v4** (`AwsSigV4Signer`, verified vs the `aws-sig-v4-test-suite` get-vanilla
    known-answer + temp-credential session token; applied in `RestExecutionService`, Auth-tab fields)
    + **Digest** (`DigestAuthenticator`, RFC 2617/7616 qop=auth/MD5, verified vs the RFC 2617 §3.5
    known-answer; 401-challenge single-retry in `RestExecutionService`)
    + **HMAC** (`HmacAuthenticator`: generic shared-secret signer over a templated canonical
    string — `{method}/{path}/{query}/{url}/{host}/{date}/{body}/{body-sha256-hex|base64}/{keyId}`
    placeholders + `\n` escape; HmacSHA256/SHA1/SHA512, hex/base64; emits `Date` when signed;
    applied in `RestExecutionService`, full Auth-tab UI; **5/5 tests** incl. the RFC 4231 §4.3 KAT)
    + **NTLM** (`NtlmAuthenticator`: NTLMv2/MS-NLMP Type 1/2/3 message generation, hand-rolled
    RFC 1320 MD4 — no new deps — + HMAC-MD5; vectors match MS-NLMP §4.2.4 NTOWFv2/NTProofStr & RFC 1320
    MD4; injectable timestamp/client-challenge for determinism; **6 tests**. **Now wired end-to-end:**
    NTLM `AuthType` + domain/username/password/workstation on `RestRequest` (interpolated); the
    connection-bound 401→Type2→Type3 handshake in `RestExecutionService` (with a documented java.net.http
    connection-pooling caveat, verified by an offline loopback-server handshake test); full Auth-tab UI +
    code-gen placeholder)
    + **cURL import** (`CurlImporter.fromCurl` — parses method/URL/-H/-d(×N)/-u basic/-X and ignores
    benign flags into a `RestRequest`, inverse of the code generator; **14 tests**; surfaced via an
    **Import cURL** button + paste dialog that populates the editor);
    _Custom Script TODO_
- [-] `BodyTab` — type selector: NONE/JSON/XML/TEXT/FORM_URLENCODED done; Form-Data/GraphQL/File TODO
      — **multipart backend done:** pure RFC 7578 `MultipartFormData` encoder (text + file parts, default
      `application/octet-stream`, collision-free lazy boundary exposed via `getBoundary()`/`getContentType()`,
      binary-safe `ByteArrayOutputStream` assembly, WHATWG percent-escaped names); 10 tests. _(BodyTab UI wiring TODO.)_
  - [x] Body text editor with JSON format button
  - [x] **Response body JSON syntax highlighting** — `ui.util.JsonView` (RichTextFX `CodeArea`) colours keys/strings/numbers/bool/null in the REST response body (Pretty/Raw modes; content-aware so XML/hex/errors stay plain). Same viewer reused in the **gRPC** and **GraphQL** response panes.
  - [ ] Form-Data table with file picker per row
- [ ] `PreRequestTab` — JavaScript/Groovy script editor
- [x] `SettingsTab` — UI tab wiring connect/read timeouts + follow-redirects to the request (honored per-call by `RestExecutionService`) _(TLS/cert selection TODO)_
- [-] Response panel:
  - [x] Status badge (color-coded: 2xx green, 3xx blue, 4xx amber, 5xx red, err red)
  - [x] Timing: total, TTFB, download shown _(DNS/TCP/TLS split needs OkHttp listener)_
  - [-] `BodyViewer` — `BodyFormatter` (protocol-http, 9 tests) + **View:** mode selector on the Body tab:
        **Pretty** (auto JSON or XML indent, content-type + sniff, XXE-hardened), **Raw**, **Hex** (offset/
        hex/ASCII dump). Switching re-renders the last response. _HTML render / image preview TODO_
  - [x] `HeadersViewer` — text view _(sortable table TODO)_
  - [x] `CookiesViewer` — `RestExecutionService` owns a per-session `CookieJar`: captures `Set-Cookie` (incl. on the Digest 401 challenge), auto-injects the matching `Cookie` header unless the user set one, toggle via `setCookieJarEnabled`; **Cookies** response tab renders the jar. 3 in-process-server wiring tests (73 total in http module)
  - [x] `TimelineViewer` — `TimelineView` Canvas waterfall (DNS/Connect/TLS/Waiting/Download bars laid end-to-end, proportional widths + per-phase ms + total); **Timeline** response tab, redraws on resize. Cumulative model already supports the finer DNS/TCP/TLS split when the OkHttp breakdown lands _(JDK client currently fills Waiting+Download)_
  - [x] `TestResultsPanel` — editable **Tests** request tab (`AssertionSpec` rows: type combo + header/JSON-path + expected/min + max) compiles to the `ResponseAssertions` backend; evaluated after every call into a **Test Results** response tab (tab title shows `n/m passed`, ✔/✘ per assertion). Assertions persist in history replay; `${VAR}` resolved in expected values. `AssertionSpec` + 3 tests (76 in http module)
- [x] Code generation panel — `RestCodeGenerator` + `CodeGenDialog` (cURL / Python / JavaScript / Java / PowerShell), copy-to-clipboard; `</>` button on the REST bar _(Go TODO)_
- [x] **HAR 1.2 export** — `HarExporter` renders a `RestRequest` + `RestResponse` into a valid HTTP Archive
      1.2 log (version/creator/entries[] with request incl. query string + `postData`, response incl. `content`,
      `timings`, `startedDateTime`, `time`); pure + dependency-free (hand-rolled RFC 8259 JSON with `\uXXXX`
      escaping, matching the module's existing style); single-entry + list overloads; unmeasured phases as -1.
      6 tests. **Wired into `RestClientView`:** a **HAR** button in the URL bar exports every request/response
      executed in the tab (multi-request session capture — each `send()` retains the interpolated exchange) to
      a `.har` file via a save dialog.
- [x] **Media-type / Content-Type parser** — pure `MediaType` (RFC 7231 §3.1.1.1): `type/subtype` +
      case-insensitive token/quoted parameters (order preserved), `charset()`/`boundary()`/`isMultipart()`/
      `isText()`/`essence()`, wildcard-aware `matches()` (`application/*`, `*/*` for Accept handling), quoting
      `toString()` round-trip; `MediaTypeParseException` on malformed input. 23 tests.
- [x] **Cache-Control parser (RFC 7234)** — pure `CacheControl`: boolean directives (`no-cache`/`no-store`/
      `public`/`private`/`immutable`/…) + delta-seconds (`max-age`/`s-maxage`/`max-stale`/`min-fresh`/
      `stale-while-revalidate`/…), quoted field-list args, case-insensitive, lenient (bad directive skipped,
      unknowns preserved); typed accessors (`noStore()`/`maxAge()`→OptionalLong/…) + round-tripping `toString()`. 18 tests.
- [x] **Link header parser (RFC 8288)** — pure `LinkHeader.parse(String)` → immutable links with `uri` +
      case-insensitive params, `rel()`/`rels()`/`title()`/`type()`/`hreflang()`, and `byRel("next")`/`first()`
      pagination helpers; lenient (blank→empty, uri-less entries skipped), handles quoted values with embedded
      commas/semicolons + `\"` escapes. 14 tests.
- [x] **JWT decoder (inspection-only)** — pure `JwtDecoder.decode(String)` → `DecodedJwt`: Base64URL-decodes
      header/payload (dependency-free JSON reader), exposes `alg`/`typ`/`kid` + standard claims
      (iss/sub/aud/exp/iat/nbf/jti) and `claim(name)`, with `exp`/`iat`/`nbf`→`Instant` and
      `isExpired`/`isNotYetValid`; **never verifies the signature** (documented; for the bearer-token inspector
      UX). `JwtDecodeException` on malformed input. 22 tests.
- [x] **URI Template (RFC 6570) expander** — pure `UriTemplate.expand(template, vars)` (Levels 1-3): `{var}`,
      reserved `{+var}`, fragment `{#var}`, multi `{x,y}`, label `{.x}`, path `{/x}`, path-param `{;x}`,
      query `{?x,y}`/`{&x}`, plus prefix `{var:3}` and explode `{var*}` over String/List/Map values, with
      operator-aware percent-encoding and undefined-var omission; `UriTemplateException` on malformed input. 21 tests.
- [ ] Request history sidebar integration
- [x] Caffeine cache: DNS cache (TTL=30s), TLS session cache (TTL=300s) — pure `DnsCache`
      (nexuslink-core/net) memoizes host→addresses through the existing `CacheRegistry.DNS` region (30s TTL);
      injectable `Resolver` seam (defaults to `InetAddress.getAllByName`), failures not cached, per-instance
      hit/miss stats (8 tests). **Wired** into `NetworkProbes.dnsResolve` so every Diagnose run shares one
      30s cache (detail shows "(cached)" on a hit); injectable-cache overload tested. TLS session resumption
      is handled natively by the JDK `SSLSessionContext` (no app-level cache needed).

### 3.2 WebSocket Client
- [x] `WebSocketService` — JDK `java.net.http.WebSocket`, text frame reassembly _(auto-reconnect TODO)_
- [x] `WebSocketView` — URL bar, connect/disconnect, message log, send bar
- [x] Message log: direction arrow, timestamp, content (text) _(binary/ping/pong TODO)_
- [-] Send panel: text send done _(binary toggle, file send, repeat/interval TODO)_

### 3.3 SSE Client
- [x] `SseService` — JDK HTTP client streaming `text/event-stream` (data/event/id parsing, background thread, disconnect). **Verified live: 162 events from the Wikimedia firehose.**
- [x] `SseView` — URL, Connect/Disconnect, live event log, event-type filter, pause/clear; wired into shell (File menu + sidebar + SSE samples)

---

## PHASE 4 — KAFKA CLIENT

**Goal:** Full Kafka tooling — producer, consumer, admin, schema registry, monitoring

> **Status:** first cut built in `nexuslink-protocol-kafka` (`KafkaService` + `KafkaExplorer` + `KafkaView`).
> Compiles + app boots clean; **needs a live broker for end-to-end testing** (no public broker / Docker here).

### 4.1 Connection
- [-] `KafkaConnectionProfile` — bootstrap + security map (security.protocol / SASL mechanism+jaas / SSL) built in `KafkaView`; _saved-profile fields TODO_
- [x] `KafkaConnectionService` — `KafkaService.connect()` creates an `Admin` client and verifies with a `listTopics` round-trip
- [x] Connection wizard with per-step diagnostics (DNS → TCP → TLS → SASL → Admin API) — pure reusable
      `ConnectionDiagnostics` runner (ordered `Probe`s, stop-on-first-failure → rest SKIPPED, per-step timing +
      live callback, `allPassed()`; 7 tests) + concrete `NetworkProbes` (DNS resolve, TCP connect, TLS
      handshake, `basicSteps(host,port,tls)`; 6 tests vs a local socket). Wired via a reusable
      `DiagnosticsDialog` (live Step/Status/Detail/Time table, off-FX) behind a **Diagnose** button on the
      Kafka connect bar (DNS→TCP to the first broker). _(protocol-specific SASL/Admin probes can be appended by callers.)_

### 4.2 Topic Browser & Admin
- [x] `TopicTreeView` — `KafkaExplorer` + `ResourceExplorerView`: topics (partition/replication counts) → partitions (leader/replicas/ISR)
- [-] `TopicDetailPanel` — partition leader/replica/ISR in the details panel; _configs table + alter + reassignment TODO_
- [x] **Topic create / delete** — `KafkaService.createTopic(name, partitions, replicationFactor)` +
      `deleteTopic(name)` over the Admin client; **live-verified** by `KafkaLiveIT` (create→describe→delete) against
      the local Kafka broker. _(UI create/delete dialog + alter-configs TODO.)_
- [x] **Config diff** — pure `ConfigDiff.compare(desired, current[, readOnlyKeys])` (Kafka-type-free `Map<String,String>`)
      classifies each key ADDED/REMOVED/CHANGED/UNCHANGED with old/new values + a read-only flag; `changesToApply()`
      (actionable set), `applicableChanges()`/`readOnlyChanges()`, `hasChanges()`; key-sorted, immutable. 17 tests.
      _(Backs a future topic/broker config editor with alter-config preview.)_

### 4.3 Producer
- [x] `KafkaProducerService` — `KafkaService.send()` (acks=all, lazy producer); _idempotent/transactions TODO_
- [x] Produce panel — topic/key/value editor, Send result shows partition + offset

### 4.4 Consumer
- [x] `KafkaConsumerService` — `KafkaService.startConsuming()` background poll loop (group, earliest/latest, wakeup-stop)
- [x] Consume panel — topic/group/from-beginning, Start/Stop, live record log (partition/offset/key/value)
- [-] Deserializer selector per key/value: String/JSON/Hex/Base64 done (`PayloadFormatter`, protocol-kafka,
      tolerant JSON pretty-printer + hex/base64, 9 tests; **Format** combo in the consume toolbar re-renders
      the key/value columns, raw values still exported). _Avro/Protobuf (need schema deps) TODO_

### 4.5 Message Browser
- [x] Poll-based browser (no consumer group side effects) — `KafkaService.browse(topic, max, fromBeginning)`
      reads with NO `group.id`, manual `assign` (never `subscribe`), `enable.auto.commit=false`, seeking to
      begin/end — so it never joins a group, commits, or rebalances. Wired as a **Browse 100** button on the
      Consume tab (fills the table off-FX). Verified by `KafkaLiveIT.browseReadsWithoutConsumerGroupSideEffects`
      (produces 2 → browses 2 → asserts no consumer group was created).
- [x] Filters: offset range, timestamp range, key contains, value contains, header filter — pure
      `MessageFilter` (protocol-kafka, immutable fluent builder over a Kafka-type-free `Record` view;
      AND-combined offset/timestamp/partition + key/value substring-or-regex w/ case flag + header
      presence/equals/contains; eager regex compile; 11 tests). Wired into the consume tab as a live
      filter bar (key/value/partition + Regex/Case toggles) over a `FilteredList`, "showing n of m"
- [x] Export selected messages as JSON/CSV — consume panel upgraded to a multi-select `TableView`
      (time/partition/offset/key/value, bounded at 10k rows); **Export JSON/CSV** writes selected rows
      (or all when none selected) via FileChooser. `KafkaMessageExporter` (protocol-kafka, hand-rolled
      RFC 8259 JSON + RFC 4180 CSV, no new deps) — 6 tests

### 4.6 Consumer Group Monitor
- [-] `ConsumerLagService` — **done (core):** `ConsumerLagCalculator` (pure, Kafka-type-free via a
      nested `TopicPartitionKey`) combines committed + end offsets → sorted `LagRow`s with `max(0,end−committed)`
      clamp + `totalLag` (9 tests); `KafkaService.listConsumerGroups()`/`consumerGroupLag(group)` fetch
      committed (`listConsumerGroupOffsets`) + end (`listOffsets` latest) via the existing AdminClient.
      Now surfaced in a **Consumer Lag** tab: editable group combo + Load groups, Refresh into a
      Topic/Partition/Committed/End/Lag table (numeric right-aligned), "Total lag", and an Auto-refresh-5s
      `Timeline` (overlap-guarded, stopped on toggle-off/empty/failure). _(live methods need a broker for E2E.)_
- [x] Lag table data: group, topic, partition, committed offset, end offset, lag — produced by `ConsumerLagCalculator`, shown in the Consumer Lag tab
- [ ] Lag chart: real-time line chart per partition over time
- [-] Offset reset dialog: earliest/latest/specific timestamp/specific offset — pure `OffsetResetPlanner`
      done (Kafka-type-free, reuses `ConsumerLagCalculator.TopicPartitionKey`): given per-partition
      begin/end/committed maps it computes a target-offset plan for EARLIEST / LATEST / SPECIFIC_OFFSET /
      TIMESTAMP (broker-resolved, LATEST fallback) / SHIFT_BY, every target clamped to `[begin, end]`;
      `ResetRow` carries current → target + signed `delta()`, plus `affectedPartitions()`. Rows sorted
      topic→partition; partitions lacking an end offset skipped. 12 tests. **UI wired:** a **Reset offsets…**
      button on the Consumer Lag tab opens `OffsetResetDialog` (strategy combo + value/timestamp fields) →
      **Preview** shows a Topic/Partition/Current/Target/Δ table, **Apply** commits it. `KafkaService.
      previewOffsetReset` fetches committed+begin+end (+timestamp) offsets via AdminClient and runs the
      planner; `applyOffsetReset` commits via `alterConsumerGroupOffsets` (needs an inactive group). Both off the FX thread.

### 4.7 Schema Registry
- [x] `SchemaRegistryClient` — Confluent-style REST client (list subjects, list versions, get schema by
      subject+version or by global id, register a version; optional HTTP basic auth) over `java.net.http`.
      Responses parsed by a dependency-free `SchemaRegistryJson` reader/writer (full string-escape +
      `\uXXXX`), so the module stays JSON-lib-free like `KafkaMessageExporter`. 12 tests (7 parser incl.
      embedded escaped schema + round-trip, 5 loopback-server client). **Wired into `KafkaView`** as a
      **Schema Registry** tab (registry URL + optional basic auth, independent of the broker connection).
      _(Caffeine cache TODO.)_
- [x] Subject list, version history, schema viewer (Avro/Protobuf/JSON Schema) — `KafkaView` Schema Registry
      tab: **Load subjects** → subjects `ListView`; selecting a subject loads its versions into a combo;
      picking a version shows the schema (JSON-highlighted, latest selected by default); **Register…** dialog
      posts a new schema version and refreshes. All calls run off the FX thread via `runBg`.
- [x] Compatibility mode display + change dialog — `SchemaRegistryClient` gains `getGlobalCompatibility`,
      `getSubjectCompatibility` (null → inherits global via a 404-tolerant GET), `setGlobalCompatibility`,
      `setSubjectCompatibility` (PUT /config[/{subject}]) + a `COMPATIBILITY_LEVELS` list; 4 added loopback
      tests (9 in `SchemaRegistryClientTest`). Wired into the Schema Registry tab: selecting a subject shows
      its effective level in a combo tagged "(override)"/"(inherited)"; **Set** applies a per-subject override.
- [x] Schema evolution diff (side-by-side version compare) — pure `SchemaDiff.between(old, new)`: parses each
      Avro record schema via the module's `SchemaRegistryJson`, canonicalizes field types (unions → `union[a,b]`),
      and returns name-ordered `FieldChange`s (`ADDED`/`REMOVED`/`TYPE_CHANGED`, nullability shows as a type
      change), an `unchanged` count, and an `isCompatible()` (additive-only) flag. 17 tests. _(UI compare view TODO.)_

### 4.8 Kafka Metrics
- [-] `KafkaMetricsService` — polls JMX or AdminClient metrics on 10s interval — AdminClient path done:
      pure `KafkaMetricsSummary` curates a Kafka-type-free `name→value` map into ordered, human-formatted
      headline rows (connections, request/response rates, byte throughput, latency, I/O wait; 5 tests);
      `KafkaService.metricValues()` flattens `admin.metrics()` to plain doubles. Wired as a **Metrics…**
      button on the Consumer Lag tab (fetches off-FX → a metric/value table dialog). _(JMX + 10s polling still TODO.)_
- [ ] Throughput chart (msgs/sec, bytes/sec), error rate, partition count
- [ ] Consumer lag summary heatmap

---

## PHASE 5 — ENTERPRISE MESSAGING

### 5.1 JMS Generic Client
- [ ] `JmsConnectionWizard` — provider dropdown, connection factory class, JNDI config, JAR upload
- [-] `JmsProducerService` + `JmsConsumerService` — `JmsService` (new `nexuslink-protocol-jms` module,
      ActiveMQ Artemis Jakarta provider / `jakarta.jms`): connect to a broker URL, `sendText`, `receiveText`
      (timed), and a non-consuming queue `browse` (JMS `QueueBrowser`). **Live-verified** via `JmsLiveIT`
      (send→receive + browse-doesn't-consume) against Artemis 2.31.2 in `test-env`. _(generic-provider JAR
      upload/JNDI + UI panel TODO.)_
- [ ] Message type selector: Text/Bytes/Map/Object/Stream _(Text done in `JmsService`)_
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
- [x] `MqttService` — Eclipse Paho **v3.1.1** (`nexuslink-protocol-mqtt`): connect
      (tcp/ssl/ws, optional user/pass + LWT, auto-reconnect), subscribe/unsubscribe (QoS 0/1/2),
      publish (QoS + retained), streaming listener. **Verified live vs. broker.hivemq.com**
      (subscribe → publish → received the message back at QoS 1). _v5.0 TODO._
- [x] `MqttView` — broker URL, client ID, auth, QoS selector, topic subscribe/publish, live
      message log; wired into the shell (File menu + sidebar + HiveMQ public sample)
- [ ] v5 properties: user properties, message expiry, content type, correlation data
- [x] **Broker URI parser** — pure `MqttBrokerUri` parses `tcp`/`mqtt` (1883), `ssl`/`tls`/`mqtts` (8883),
      `ws` (80), `wss` (443) with scheme-default ports, `tls`/`websocket` flags, WS path (default `/mqtt`),
      percent-decoded userinfo, IPv6 hosts, round-tripping `normalized()`/`toString()` + `redacted()`;
      `MqttBrokerUriException` on malformed input. 25 tests.
- [x] **Topic-filter matching + validation** — pure `MqttTopicFilter` (spec §4.7): `compile(String)`
      returns a reusable immutable matcher (`+`/`#` wildcards, single/multi-level, parent-level match,
      `$`-topic exclusion, empty levels), plus `isValidFilter`/`isValidTopicName` per §4.7.3 (whole-level
      wildcards, `#` only last, no wildcards in names, no U+0000, ≤65535 UTF-8 bytes). Index-walking, no
      per-match allocation; 71 tests. _(UI subscription-overlap highlighting can build on this.)_
- [-] Message history — live in-session log (timestamp, QoS, retained flag); _persistent history TODO_

### 5.5 RabbitMQ
- [-] `RabbitMqService` — AMQP 0.9.1 client (official `amqp-client`): connect (URI/host[:port] +
      credentials), declare exchange/queue/binding, publish (persistent), consume w/ auto-ack
      (`nexuslink-protocol-rabbitmq`, **7/7 tests** on the pure `factoryFor` seam); **Live E2E verified** via
      `RabbitMqLiveIT` (publish→consume) against the local broker. _Management REST API TODO_
- [x] **AMQP URI parser** — pure `AmqpUri` (AMQP 0-9-1 / RabbitMQ URI spec): `amqp`(5672)/`amqps`(5671, tls),
      percent-decoded userinfo + vhost, the absent-vs-empty vhost rule (`amqp://host`→`/`, `amqp://host/`→``),
      `%2f` in vhost, query options (`heartbeat`/`connection_timeout`/`channel_max`), IPv6 hosts, `redacted()`;
      `AmqpUriException` on malformed input. 29 tests.
- [x] `RabbitMqView` — declare exchange/queue/binding, publish to exchange+routing-key, consume a
      queue into a live message log; `${VAR}` resolved in every field; **File ▸ New RabbitMQ Client**
- [-] DLX config viewer, publisher confirms, manual ack/nack/requeue, message properties editor
  - [x] **Publisher confirms + manual ack/nack** (backend) — `RabbitMqService.publishConfirmed(...)` enables
        `confirmSelect()` once per channel, publishes, and `waitForConfirms(timeout)` → a pure
        `PublishConfirm` enum (ACKED/NACKED/TIMEOUT, `fromWaitForConfirms` seam, 3 tests); no-auto-ack
        `consumeManual(queue)` passes the delivery tag through, plus `ack(tag)`/`nack(tag, requeue)`.
        _(wiring into `RabbitMqView` UI still TODO; live paths need a broker for E2E)_
  - [x] `DeadLetterArgs` — DLX queue-declare args builder (`x-dead-letter-*`, TTL, max-length, overflow); 8 tests
  - [x] `RabbitMqManagementClient` — HTTP management API (overview/queues/exchanges/bindings/get/purge),
        vhost encoding, Basic auth, JSON→records; 16 tests.
  - [x] **Management dashboard** — `RabbitMqView` split into **Messaging** / **Management** tabs; Management
        has host+port (default 15672) reusing Auth credentials, **Refresh** loads overview + queues/
        exchanges/bindings tables off the FX thread, and **Purge selected queue** (confirm → purge →
        refresh); errors surfaced to status/log, never crash the UI. _(publisher confirms/ack UI still TODO)_

### 5.6 Cloud Messaging
- [-] AWS SQS: send/receive/delete, DLQ, FIFO support — `SqsService` (new `nexuslink-protocol-sqs` module,
      AWS SDK v2 + url-connection client, emulator-aware endpoint): list/create queues, send, long-poll
      receive, delete, purge, approximate-count, FIFO send (`.fifo` → content-dedup). **Live-verified** via
      `SqsSnsLiveIT` (send→receive→delete round-trip + FIFO order) against LocalStack. _(UI panel + DLQ redrive TODO.)_
- [-] AWS SNS: publish, subscription listing — `SnsService` (same module): create/list topics, publish
      (subject+message), list subscriptions, delete topic. **Live-verified** (create→publish→list) vs LocalStack.
      _(UI panel + SNS→SQS subscription wiring TODO.)_
- [ ] Azure Service Bus: queue/topic/subscription, sessions, DLQ
- [ ] Google Pub/Sub: publish, pull subscription

---

## PHASE 6 — ADVANCED HTTP PROTOCOLS

### 6.1 gRPC Client
- [x] **Server reflection** — `GrpcService` auto-discovers services/methods + resolves descriptors via reflection (recursive dependency resolution); no `.proto` upload needed. **Verified live vs. grpcb.in.**
- [x] `GrpcChannelService` — managed channel per connection (plaintext/TLS)
- [-] `GrpcInvokerService` — **unary** done (DynamicMessage ↔ JSON via JsonFormat + ProtoUtils marshallers); _server/client/bidi streaming TODO_
- [x] **gRPC status-code registry** — pure `GrpcStatusCodes` (no `io.grpc` dep): the 17 canonical codes 0..16
      (`Code` enum, ordinal == wire number), `byNumber`/`findByNumber`/`byName`/`name`/`number`/`description`,
      a gRPC→HTTP status mapping (`httpStatus`), and an immutable `all()` for UI tables. 10 tests.
- [x] `GrpcView` — host/port/TLS bar, service picker, method picker (streaming-flagged), request JSON editor, response panel; wired into the shell (gRPC sample = grpcb.in)
- [-] `ProtoFileLoader` — parse local `.proto` files (alternative to reflection) — pure dependency-free
      proto3 parser done: strips comments, extracts syntax + package + message names, and brace-matches each
      `service` to parse its `rpc`s (name, input/output types incl. fully-qualified `.pkg.Type`, client/server
      streaming flags → `Method.isUnary()`). Returns a `ProtoFile(syntax, package, services, messages)`. 7 tests.
      _(GrpcView "load .proto" wiring + request-template synthesis from message fields still TODO.)_
- [ ] Streaming panel: live message list, send message (client/bidi), end stream

### 6.2 GraphQL Client
- [-] `GraphQLService` — HTTP POST ({query, variables}) done, pretty JSON response. **Verified live vs. Countries GraphQL.** _WebSocket subscriptions TODO._
- [x] Schema introspection — one-click `Introspect` button (built-in introspection query)
- [-] Query/mutation editor — plain editor + variables JSON; schema-aware assist done: pure `GraphQLSchema`
      parses the (widened) introspection response — root operation types + every type's fields with unwrapped
      NON_NULL/LIST type names and arg names (8 tests) — wired behind a **Schema…** button that opens a
      type→fields explorer (Query root preselected); double-click a field inserts it at the query caret.
      _(inline caret-context completion popup still TODO.)_
- [x] Variables JSON editor
- [ ] Subscription live stream panel
- [x] `GraphQLView` wired into the shell (File menu + sidebar + GraphQL samples)

---

## PHASE 7 — FILE TRANSFER

### 7.1 SFTP / SCP
- [x] `SftpService` — Apache MINA SSHD; password + SSH-private-key auth, list/read + **upload/download (progress), mkdir, rename, delete (recursive), chmod**. **Verified live vs. test.rebex.net.**
- [x] `SftpView` — **WinSCP/MobaXterm-style two-pane commander** (local↔remote) via reusable `com.nexuslink.ui.files` (`FileBrowserPane`/`DualPaneBrowser`); upload/download, **cross-pane drag-and-drop**, Ctrl/Shift multi-select, New-Folder/Rename(F2)/Delete(Del), context menus.
- [x] `TransferQueue` — batch ops, pause/resume/retry/cancel, bandwidth throttle (queue panel with Pause/Resume + Limit combo; `TransferGovernor` governs the in-flight copy)
- [-] `SyncService` — bidirectional sync with conflict resolution (hash compare) — pure planning core
      done: `SyncPlanner` turns a `DirectoryDiff` into an ordered `Action` list for a chosen `Mode`
      (MIRROR_TO_RIGHT / MIRROR_TO_LEFT / UPDATE_RIGHT / UPDATE_LEFT — the two UPDATE_* modes never
      delete); each action carries the source/victim `FileItem` + the diff status that justified it, plus
      a per-`Op` `summary()` for a preview. Copies dispatch to `TransferQueue`, deletes to `FileSystem`.
      8 tests. **UI wired:** the `DirectoryCompareDialog` has a mode combo (mirror →/← · update →/← ) with a
      live "N copy → · M copy ← · D delete" plan preview; **Run sync** executes the plan through
      `DualPaneBrowser` — copies flow via the transfer queue (overwrite prompt), deletes are confirmed once
      then applied off the FX thread on the correct side. _(Hash-based change detection still TODO.)_
- [x] Permissions display (rwx string in details) + **remote chmod** (octal dialog on the SFTP pane)

### 7.2 FTP / FTPS
- [x] `FtpService` — Apache Commons Net (password/anonymous, passive, FTPS) + **upload/download (progress), mkdir, rename, delete (recursive), pwd**. **Verified live vs. test.rebex.net** + `FtpLiveIT` (upload/list/read/delete) vs local vsftpd.
- [x] **Listing-line parser** — pure `FtpListParser` (+`FtpListEntry`) for Unix `LIST` (type/perms/size/date/name,
      names-with-spaces, `l… -> target` symlinks, year-vs-time) and RFC 3659 `MLSD` (`fact=value;` pairs → type/
      size/modify); `parseUnix`/`parseMlsd`/auto-detecting `parseLine` return `Optional` (empty on unparseable);
      bulk `parse(...)` skips blanks + `.`/`..`. 14 tests.
- [x] `FtpView` — **same two-pane commander** with drag-and-drop transfers (reuses `com.nexuslink.ui.files`)

### 7.4 File Commander — parity & power features (shared `com.nexuslink.ui.files`)
> The dual-pane commander, cross-pane drag-and-drop, multi-select, mkdir/rename/delete/chmod and a
> transfer progress bar already exist (7.1/7.2). These extend it toward WinSCP/MobaXterm parity and
> apply to **every** file-style connector (SFTP, FTP/FTPS, and later S3/Azure/GCS object storage).

**Layout / always-visible panes**
- [x] **Show the LOCAL pane immediately** — the commander is now built at view construction (the remote
      `FileSystem` adapter wraps the long-lived service), so the local pane is browsable straight away and
      the remote pane reads "not connected" until Connect succeeds. `DualPaneBrowser.startLocal()/
      connectRemote()/disconnectRemote()` + `FileBrowserPane.showDisconnected()`; transfers guarded while
      disconnected. Local navigation now survives connect/disconnect (browser no longer rebuilt). SFTP+FTP.
- [x] Keep the connect bar **and** the dual pane visible together (the body is no longer swapped for a placeholder)

**Per-pane navigation**
- [-] Address/path bar with manual path entry + breadcrumb; **Up**, **Home**, back/forward history —
      **done:** editable address field + a clickable **breadcrumb trail** (pure `PathCrumbs` helper walks
      up via the FileSystem's parent fn, works for POSIX + local paths; 5 tests) + Up button. _(back/forward
      history still TODO)_
- [x] Default listing order: ".." first, directories before files, case-insensitive name (pure `FileOrder`
      comparator) — **interactive click-to-sort columns done:** clicking a Name/Size/Modified/Permissions
      header picks the sort key + direction through a single `TableView` sort policy while the ".." row and
      dirs-first grouping are always preserved (only the key flips with direction); `FileOrder.by(SortKey,
      ascending)` is the pure, JavaFX-free seam both the policy and the default listing share, so a chosen
      sort persists across navigation. 9 `FileOrderTest` tests.
- [x] Hidden-files toggle; in-pane quick filter/search box — a **• Hidden** toggle + a **Filter…** field
      on each pane's bar, both routed through the pure JavaFX-free `FileFilter` (dotfile hide + case-insensitive
      name substring; ".." always kept) that `FileBrowserPane.applyView()` composes with the sort policy. 6 tests.
- [x] Synchronized browsing — a **⇄ Sync** toggle in the commander mirrors each pane's navigation onto
      the other by relative path (descend into the same sub-folder / climb the same number of levels); pure
      JavaFX-free `SyncBrowsing` path math (8 tests) with a suppression counter to break the mirror loop;
      enabling it snapshots both paths so it never jumps on activation.
- [-] Per-pane status line: item count, **selected count + total size** done (each pane shows
      `N selected · <size>` on selection, reverting to the item count otherwise; pure reusable
      `FileItem.humanSize(long)`, 2 tests). _(free space TODO.)_

**Transfers**
- [-] `TransferQueue` panel — **done:** observable engine driving existing `FileTransfer`, sequential
      worker, collapsible `TransferQueuePanel` (per-row progress bars, overall bar, live counts,
      clear-completed), enqueued from buttons/double-click/drag-drop; **speed & ETA** — items stamp
      start/finish nanos, expose `bytesPerSecond`/`etaSeconds` (pure helpers + human formatters), queue
      exposes `activeBytesPerSecond`; panel shows a per-row Speed column + overall rate in the footer;
      **cancel** (QUEUED→CANCELLED, worker skips it) + **retry** (FAILED/CANCELLED→QUEUED via
      `retry`/`retryAllFailed`) + **reorder** (`move` a QUEUED item up/down without displacing
      active/terminal ones) with a row context menu + "Retry failed" button (20 tests).
      **pause/resume of an in-flight transfer + bandwidth throttle** now done via `TransferGovernor`
      (JavaFX-free; blocks/sleeps inside the cumulative-bytes progress callback, so no SFTP/FTP/local
      code changes) — `queue.pause()/resume()/setMaxBytesPerSecond()`; panel gets a Pause/Resume button
      + a Limit combo (Unlimited/512 KB/1/5/10 MB/s). Injectable clock+sleeper → 4 governor tests +
      1 queue-delegation test.
- [x] **Recursive directory transfers** — `TransferQueue.enqueueRecursive` walks a selected folder
      (via the source `FileSystem`), recreates the tree on the destination side (`mkdir`), and enqueues
      every contained file into the normal sequential worker; `DualPaneBrowser` no longer filters out
      directories — folder selections scan off the FX thread, then flow with the shared overwrite
      resolver. 3 added tests (12 in `TransferQueueTest`)
- [-] Conflict resolution on transfer — **done:** prompt **skip / overwrite** with **overwrite-all /
      skip-all** stickiness across a batch (`OverwriteResolver`). **TODO:** overwrite-if-newer / rename
- [-] **Resume** interrupted/partial transfers (offset-based); auto-retry on transient errors —
      **auto-retry done:** pure `RetryPolicy` (max attempts + exponential capped backoff) +
      `TransferErrors.isTransient` (timeout/reset/refused/unreachable → retriable; bad-creds/missing-file/
      unknown-host → permanent, scans the cause chain); wired into `TransferQueue` (per-item attempt
      count, injectable backoff sleeper, off by default) behind an **Auto-retry** toggle in the queue
      footer. 13 tests (4 RetryPolicy, 5 TransferErrors, 4 queue). _(offset-based resume of a partial file
      still TODO — needs append/offset support in `FileTransfer`.)_
- [ ] Parallel/background transfers (configurable concurrency)
- [-] Post-transfer integrity check (size/mtime, optional hash/checksum) — pure `TransferIntegrity`
      verifier done: compares source vs landed destination — byte count always, plus a checksum when the
      caller can hash both sides (case-insensitive, whitespace-trimmed) — returning a `Report` with a
      precise `Issue` list (DESTINATION_MISSING / SIZE_MISMATCH / CHECKSUM_MISMATCH). 8 tests. **Wired into
      `TransferQueue`:** a **Verify** toggle in the queue-panel footer (`setVerifyIntegrity`) size-checks each
      completed file against the destination listing; a mismatch/missing marks it FAILED (retryable) with an
      "integrity check failed: …" note. 4 added queue tests (24 in `TransferQueueTest`). _(Hash computation still TODO.)_

**Drag & drop**
- [ ] **External DnD** — drag files in from the OS file manager (upload) and out to the desktop (download)
- [-] Move-vs-copy semantics via modifier keys, incl. server-side move within remote and within local —
      cross-pane **move** done: `TransferItem` carries a `moveMode` flag, `TransferQueue.enqueue(…, moveMode)`
      deletes each source only after its copy succeeds (a skipped/failed copy leaves the source intact);
      **Move →** / **← Move** buttons (confirmed) in `DualPaneBrowser`, both panes refresh after. 3 added
      queue tests (27 in `TransferQueueTest`). _(modifier-key DnD + same-side/server-side move via rename still TODO.)_
- [ ] Drop directly onto a **target folder row** (not only the pane's current directory)
- [ ] Drag-over highlight / drop-target affordance for the above _(cross-pane highlight already done)_

**File operations**
- [-] Batch/multi-file rename; duplicate; **copy-path** — copy-path done (context menu + Ctrl+Shift+C copies
      the full path(s) of the selection to the clipboard); **batch rename** engine done: pure `BulkRename`
      previews before → after for a selection with find/replace (literal or `$1`-backref regex),
      prefix/suffix, `{n}` sequential numbering (start/step/zero-pad) and a case transform — all
      extension-aware (dotfiles/extensionless handled) — and flags colliding targets on every affected
      row. 12 tests. **UI wired:** a **Batch rename…** context-menu item on each pane opens `BatchRenameDialog`
      (find/replace + Regex, prefix/suffix, Add-number spinners, Case combo) with a live before → after preview
      that colours colliding rows red and disables OK until the plan is unambiguous and non-empty; accepting
      renames the changed rows sequentially off the FX thread via `FileSystem.rename`. _(Duplicate still TODO.)_
- [x] Properties dialog (size, permissions, owner, timestamps) — a **Properties…** context-menu item opens a
      read-only name/type/path/size/modified/permissions grid built from the pure `FileDetails.of(FileItem)`;
      includes symbolic→octal permission conversion (`permissionsOctal`, handles the type prefix + setuid/sticky). 6 tests.
- [ ] Quick view/preview (text/image) and **edit-in-place** for remote files (download → edit → upload on save)
- [-] Compare directories (highlight new/changed/missing) — pure `DirectoryDiff` seam done: single-level
      compare of two listings, matching by name (case-sensitive by default, optional case-insensitive for
      Windows FS), classifying each entry LEFT_ONLY / RIGHT_ONLY / DIFFERENT (size, mtime, or file-vs-dir
      type) / SAME (same-name dirs match at this level); ignores the ".." row, returns a merged dirs-first
      view + a per-status `summary()` count. Feeds `SyncService` (7.1). 9 tests. **UI wired:** a **⇋ Compare**
      button in the commander opens `DirectoryCompareDialog` — a status-tinted name·left·right·status table
      (new-left/new-right/differs/identical, with a "show identical" toggle) + a header summary count.

**Sessions & integration**
- [-] Bookmarks / saved sessions / quick-connect; remember last local+remote dirs per session — folder
      **bookmarks** done: pure `PathBookmarks` (ordered, path-unique add/remove/contains, tab-separated
      serialize/parse + file load/save; 10 tests) wired into each `FileBrowserPane` as a **★** dropdown —
      bookmark/un-bookmark the current folder + one entry per saved location that navigates to it, persisted
      per file-system under `~/.nexuslink/bookmarks-<name>.txt`. _(Saved sessions / quick-connect / last-dir memory still TODO.)_
- [x] Norton-Commander keyboard shortcuts (F5 copy · F6 rename · F7 mkdir · F8 delete · Tab switches panes) —
      handled at the `DualPaneBrowser` level over the active (last-focused) pane, with a matching clickable
      function-key bar along the bottom; text-field edits are not hijacked. _(F6 is rename; true move TODO.)_
- [ ] Embedded SSH terminal alongside SFTP ("Open terminal here") — depends on 8.5 SSH Terminal
- [ ] SCP transfer mode in addition to SFTP
- [ ] **Reuse the commander + transfer queue + DnD for object storage** (S3/Azure/GCS) once their upload/put lands (7.3)

### 7.x Connection-type visibility (per-user)
- [x] **Enable/disable protocols** — data-driven protocol catalog; View ▸ Protocols… dialog toggles which connection types appear in the menu + sidebar, persisted via Preferences (`ProtocolPrefs`). Each user sees only the connectors they use.

### 7.3 Object Storage
- [-] `S3Service` — AWS SDK v2 (URL-connection client), S3-compatible (AWS/MinIO/Wasabi), path-style; connect, listBuckets, listObjects, getObjectAsText, **putObject/putText + deleteObject** (upload). **Verified live: 647 buckets from MinIO Play** + `S3LiveIT` (list/get **and upload→get→delete**) vs LocalStack. **Presigned URLs** done: pure `S3PresignedUrl` — AWS SigV4 query-string presigner for GET/PUT (virtual-hosted + path-style, optional session token, `UNSIGNED-PAYLOAD`, `javax.crypto` only, no SDK / cross-module dep); 22 tests incl. a known-answer signature. _Versioning / multipart + UI "copy presigned link" TODO._
- [x] **S3 URI parser** — pure `S3Uri.parse(...)` → `{bucket, key, region, endpoint, style}` for `s3://`,
      virtual-hosted (`bucket.s3[.-]region.amazonaws.com`) and path-style (`s3.region.amazonaws.com/bucket/key`
      + custom endpoints like LocalStack `localhost:4566/bucket/key`) URLs; URL-decodes the key (keeps literal
      `+`), `toS3Uri()` canonicalization, `S3UriException` on malformed input. 25 tests.
- [x] `S3Explorer` + `S3View` — bucket → object tree with size/modified/etag details (reuses `ResourceExplorerView`); wired into the shell (new `nexuslink-protocol-s3` module, S3 sample opens prefilled)
- [-] `AzureBlobService` — Azure SDK (connection string / shared key); connect, listContainers, listBlobs. `AzureBlobExplorer` + `AzureBlobView` (container → blob tree). Azurite sample. **Live E2E verified** via `AzureLiveIT` against the local Azurite emulator. _SAS tokens / tiering / upload TODO._
- [x] **Azure connection-string parser** — pure driver-free `AzureConnectionString.parse(...)` of `Key=Value;`
      pairs (case-insensitive): protocol/account/key/endpointSuffix/SAS + explicit `BlobEndpoint` overrides,
      the `UseDevelopmentStorage=true` Azurite shortcut (`isDevelopment()`), computed
      `blobEndpoint()`/`queueEndpoint()`/`tableEndpoint()`/`fileEndpoint()`, and a `redacted()` masking the key/SAS;
      `MalformedConnectionStringException` on bad input. 18 tests.
- [-] `GcsService` — Google Cloud Storage client (project + service-account JSON / ADC); connect, listBuckets, listObjects. **Now emulator-aware** (honours `STORAGE_EMULATOR_HOST` with anonymous creds) and **live E2E verified** via `GcsLiveIT` against fake-gcs-server. `GcsExplorer` + `GcsView`. _Signed URLs / upload TODO._
- [x] **GCS URI parser** — pure `GcsUri.parse(...)` for `gs://bucket/object`, path-style
      (`storage.googleapis.com`/`storage.cloud.google.com/bucket/object`) and virtual-hosted
      (`bucket.storage.googleapis.com/object`) URLs → `{bucket, object}`, URL-decoding the object;
      `toGsUri()` canonical form; `GcsUriException` on malformed input. 8 tests.
- [x] Shared bucket/container browser view — **S3 + Azure Blob + GCS** all use the same `ResourceExplorerView` BUCKET→OBJECT pattern

---

## PHASE 8 — DATABASE & ENTERPRISE PROTOCOLS

### 8.1 JDBC SQL Client
- [x] `JdbcService` — DriverManager connection (now accepts extra driver props), SELECT/update detection _(HikariCP pool TODO)_
- [x] **JDBC URL parser/builder** — pure `JdbcUrl` parses/rebuilds URLs for PostgreSQL, MySQL, MariaDB, SQL Server
      (`;`-props + `databaseName`), Oracle thin (service `@//host:port/svc` and legacy SID `@host:port:sid`), and
      SQLite; fills vendor default ports, keeps insertion-ordered params, handles IPv6 hosts, round-trips canonical
      forms, throws `JdbcUrlException` on malformed input. 24 tests. _(Wire into the SQL connect form field-splitting.)_
- [x] **SQL tokenizer** — pure `SqlTokenizer` → `SqlToken(type,text,start,end)` spans (contiguous, round-trips
      the input) with `SqlTokenType` KEYWORD/IDENTIFIER/STRING/QUOTED_IDENTIFIER/NUMBER/OPERATOR/LINE_COMMENT/
      BLOCK_COMMENT/PARAMETER(`?`/`:name`/`$1`)/WHITESPACE; case-insensitive keyword set (`keywords()`/`isKeyword`),
      best-effort (never throws; unterminated string/comment run to EOF). Feeds offline editor highlighting. 27 tests.
- [x] **SQL script splitter** — pure `SqlScriptSplitter` splits a script into statements on `;`, ignoring
      semicolons inside single-quoted literals (`''` escapes), `"`/`` ` `` quoted identifiers, `--`/`#` line
      comments, `/* */` block comments (optional nesting), and Postgres `$$`/`$tag$` dollar-quoting;
      comment-only fragments dropped by default (retainable), plus a `splitWithOffsets` variant returning
      `Statement(text, startOffset)`. 20 tests. _(Wire into SqlView for run-all / run-at-cursor.)_
- [x] **Driver-specific TLS/SSL** — `SslMode` + `JdbcTlsSpec` → `JdbcTlsParams` maps generic TLS material to
      Postgres/CockroachDB (`ssl`/`sslmode`/`sslrootcert`/`sslcert`/`sslkey`), MySQL (`sslMode` + `*KeyStoreUrl`),
      MariaDB (`serverSslCert`/`trustStore`/`keyStore`), SQL Server (`encrypt`/`trustServerCertificate`);
      SQL view has a collapsible **TLS / SSL** pane. 11 tests, offline.
- [x] `SqlClientView` — SQL editor (Ctrl+Enter), run button, result grid
- [x] Schema browser — `JdbcExplorer` + `ResourceExplorerView` lazy tree (database → tables/views → columns, types in details; double-click a table to query) _(indexes/procedures tree TODO)_
- [x] Result grid: rendered, **sortable** (header click), **live filter** (`SortedList`→`FilteredList`, any-cell case-insensitive), and **Export JSON/CSV** of the displayed rows via FileChooser. `ResultGridExporter` (protocol-db, hand-rolled RFC 8259/4180, no deps) — 8 tests
- [x] **4/4 unit tests pass** (in-memory SQLite)
- [x] **ER diagram** — `JdbcService.erDiagramMermaid()`/`erDiagramMermaid(tables)` builds a Mermaid `erDiagram` from tables/columns/PK/FK; "ER Diagram" button opens a **table-picker** (choose which tables to include; dangling relationships to excluded tables are dropped) then renders it in `DiagramView` (zoom/pan + **Export SVG/PNG**). Unit tests cover entities + relationship + filtered selection.
- [x] Query history integration (reuse HistoryStore) — `SqlClientView` records each executed statement via a
      `setHistoryRecorder` hook (like REST): `HistoryEntry.newSql(summary, durationMs, detailJson)` with a
      replayable `{url, sql}` detail blob; MainWindow wires it at both SQL tab sites and routes `sql`-protocol
      replays back into a SQL tab (`loadQuery`) instead of a REST tab.
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
- [x] Redshift, BigQuery → add to catalog when needed — both added to `JdbcDriverRegistry` as on-demand cloud
      warehouses: Amazon Redshift (`com.amazon.redshift.jdbc.Driver`, Apache-2.0, no license ack) and Google
      BigQuery (Simba `com.simba.googlebigquery.jdbc.Driver`, proprietary → requires license ack). 1 added test.

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
- [-] `RedisService` — Lettuce client (`redis://` / `rediss://`); connect, SCAN keys, typed value read, command runner. **Live E2E verified** via `RedisLiveIT` against the local `test-env` stack. _Cluster + Sentinel TODO._
- [x] Data-type value rendering: String/Hash/List/Set/ZSet/Stream (in the details panel)
- [-] Command console — `RedisService.execute()` supports ~20 common commands; **auto-complete core done**:
      pure `RedisCommandCatalog` (58 commands with group/summary/syntax) + case-insensitive first-token prefix
      `complete()`, `find()`, `inGroup()`; 12 tests. _(Wire into the console editor as a completion popup TODO.)_
- [x] **Glob matcher** — pure `RedisGlob.matches(pattern, key)` mirrors Redis `stringmatchlen` (`*`/`?`,
      `[..]` classes with ranges + `^` negation, `\` escapes; whole-string, case-sensitive) so `KEYS`/`SCAN MATCH`
      listings can be filtered client-side without a round-trip. 7 tests.
- [x] **RESP codec — live-verified** — `RespCodecLiveIT` opens a raw TCP socket to a real Redis and runs
      PING/SET/GET/DEL purely through `RespCodec.encodeCommand` + `decode(InputStream)`, proving the hand-rolled
      codec interoperates with an actual server (SimpleString/BulkString/Integer/null replies).
- [x] **RESP wire codec** — pure, dependency-free `RespCodec` + sealed `RespValue` hierarchy covering
      RESP2 (simple string/error/integer/bulk incl. null, array incl. null) and RESP3 (null/boolean/double/
      big-number/bulk-error/verbatim/map/set/push). `encodeCommand(String…|List<byte[]>)` builds the client
      array-of-bulk-strings request; `decode(byte[]/ByteBuffer/InputStream)` reads one full reply (byte[]/
      buffer throw `RespIncompleteException` on a partial reply; the stream overload leaves pipelined bytes).
      Binary-safe bulk strings, UTF-8 text; 41 tests. _(raw-command console + pipeline UI can build on this.)_
- [x] `RedisExplorer` + `RedisView` (key browser with lazy value-on-select + console); wired into the shell
- [ ] Pub/Sub subscriber panel

### 8.3 MongoDB Client (separate driver — not JDBC)
> **Studio-3T-class goals:** schema diagram, Compass-style views, SQL queries — see below + Session 20.
- [x] `MongoService` — `org.mongodb:mongodb-driver-sync` (Apache-2.0) in its own `nexuslink-protocol-mongo` module: connect, list dbs/collections, find, aggregate, count, insertOne, updateMany, deleteMany (Extended-JSON in/out) + `MongoQueryResult`
- [x] **Connection-string parser** — pure driver-free `MongoConnectionString.parse(...)` for `mongodb://`
      (host list, default port 27017) + `mongodb+srv://` (single host, SRV-flagged, no DNS resolution);
      percent-decoded userinfo/db/options, case-insensitive option keys, typed helpers (`tls`/`ssl`,
      `replicaSet`, `authSource`, `retryWrites`), IPv6 hosts, password-masking `redacted()`;
      `MongoConnectionStringException` on malformed input. 22 tests.
- [x] `MongoClientView` UI — connection bar, database picker + collection list, operation selector (find/aggregate/insert/update/delete), Extended-JSON editor (Ctrl+Enter), result pane; wired into `MainWindow` (File menu + sidebar + tab opener)
- [x] Document CRUD + **visual aggregation pipeline builder** (stage-by-stage, run or load into editor); in-grid edit/delete (matched by _id) from the Table view
- [x] **SQL-like queries** (`executeSql`) beside the JSON filter — both options; **explain plan**; **export** results to JSON/CSV; **query history** (recall recent)
- [x] **Schema diagram** (inferred ER from sampled docs) + **Compass-like views** (JSON / Table / Schema with field type %). JSON/document views now use a **themed syntax-highlighting `CodeArea`** (`JsonView`, RichTextFX) — keys/strings/numbers/bool/null coloured per theme, in both the read-only result pane and the editable document dialog.
- [x] **Object explorer** (`MongoExplorer` + `ResourceExplorerView`): databases → collections → indexes tree with collStats + index definitions in the details panel
- [-] Collection stats + index manager — stats + index listing surfaced in the explorer; _create/drop index UI TODO_
- [-] Auth: SCRAM / x.509 / LDAP / Kerberos / TLS — supported via connection string (`mongodb+srv://`, TLS, SCRAM); _dedicated auth UI TODO_
- [x] Testing: `MongoServiceTest` spins up `mongo:7.0` via Testcontainers, gated behind `-DrunMongoIT=true` so the default build stays green without Docker (4 tests, skipped when the property is unset)

### 8.4 LDAP / Active Directory
- [x] `LdapService` — UnboundID SDK; connect plain/LDAPS (+ optional bind), Root-DSE naming contexts,
      base/one/sub search with RFC-4515 filter, decoded entries (`nexuslink-protocol-ldap`,
      **6/6 tests** end-to-end against the bundled in-memory directory server); _StartTLS TODO_
- [x] **LDIF + DN model** — `LdifWriter`/`LdifReader` (RFC 2849: base64 `::`, 76-char folding, multi-entry),
      `Dn`/`Rdn` (RFC 4514 parse/escape, parent/child, normalized equality), `LdapEntry`; 29 tests, offline.
- [x] **LDAP URL (RFC 4516)** — pure `LdapUrl` parse/format for `ldap[s]://host:port/dn?attrs?scope?filter?exts`:
      percent-encode/decode (UTF-8), scheme-default ports (389/636), IPv6 bracket hosts, `Scope` enum with RFC
      defaulting, filter defaulting to `(objectClass=*)`, `Extension` records with `!critical`; reuses `Dn` for
      the base DN, `toString()` omits trailing defaults and round-trips; `LdapUrlException` on malformed input.
      35 tests, offline.
- [-] `LdapView` — connect bar (host/port/bind/LDAPS), search (base/filter/scope/limit), DN result list +
      attribute detail; naming contexts pre-fill the base; `${VAR}` in every field. Left pane is now
      tabbed: **List** + **Tree (DIT)** (hierarchy built from result DNs via `Dn.parent()`/`rdn()`,
      select-to-show-attributes). **Import/Export LDIF** buttons via `LdifReader`/`LdifWriter` + FileChooser,
      fully offline (export honors a selected DIT subtree); all file I/O off the FX thread. _StartTLS TODO_
- [x] Search dialog (custom filter builder + predefined filters), entry add/modify/delete —
      `LdapFilterBuilder` (protocol-ldap, RFC 4515 compose + escape, predefined persons/groups/OUs/byUid/
      byCn, 9 tests) behind a **Build…** filter dialog; `LdapService.addEntry/modifyEntry/deleteEntry`
      (UnboundID, UI-friendly `Mod`/`ModType`, 4 in-memory-server tests); LdapView Add-child/Modify/Delete
      via toolbar + context menus on the list and DIT tree, refreshing after each write
- [x] **RFC 4515 filter parser** (inverse of `LdapFilterBuilder`) — pure `LdapFilterParser.parse(String)` →
      sealed `LdapFilter` AST (`And`/`Or`/`Not`/`Present`/`Equality`/`Substring`/`GreaterOrEqual`/`LessOrEqual`/
      `Approx`/`ExtensibleMatch`); recursive-descent, decodes `\HH` escapes and re-escapes on `render()`
      (round-trips), throws `LdapFilterParseException` on malformed input. 33 tests. **Live-verified** end-to-end:
      `LdapLiveIT` searches use RFC-4515 filters against the local OpenLDAP.

### 8.5 SSH Terminal
- [ ] `SshTerminalService` — Apache MINA SSHD
- [ ] `TerminalView` — xterm emulation (JediTerm or custom VT100 renderer)
- [ ] Multi-tab sessions, local port forwarding config

### 8.6 SNMP Browser
- [x] `SnmpService` — SNMP4J community **v1/v2c** GET + WALK (GETNEXT subtree loop w/ end-of-MIB +
      non-advancing guards), decoded varbinds (OID/type/value) (`nexuslink-protocol-snmp`,
      **4/4 tests** on the pure version/address/OID/varbind seam); _SNMPv3 USM TODO_
- [x] `SnmpView` — open v1/v2c session, GET an OID or WALK a subtree into an OID/type/value table;
      `${VAR}` in host/community/OID; **resolved MIB-name column + symbolic-name input** via `OidRegistry`.
- [x] `OidRegistry` — OID↔MIB-name (SNMPv2-MIB system/interfaces), longest-prefix + instance suffix; 13 tests
- [x] **SMI value formatter** — pure `SmiFormatter`: `formatTimeTicks` (→ `d days, H:MM:SS.ss`), unsigned
      `formatCounter`/`formatGauge` (32-bit) + `formatCounter64` (BigInteger / hi-lo), `formatIpAddress` (dotted
      quad), `formatOctetString` (printable-text-or-hex, with `looksPrintable`/`hex` helpers), and `formatOid`;
      static/side-effect-free for value display. 36 tests.
- [x] **`Oid` value type** — pure immutable numeric OID: `parse`/`toString` round-trip (optional leading dot),
      `child`/`parent`/`sub`, `Comparable` **lexicographic** ordering (the GETNEXT/walk order), `isPrefixOf`/
      `startsWith` (subtree bounds) and `next()`; sub-ids as unsigned 32-bit `long`; `OidFormatException` on bad
      input. 29 tests. _(The offline seam for tighter walk/subtree logic in `SnmpService`.)_
- [x] `SnmpV3Config` — USM model (security level + auth MD5/SHA/SHA-256 + priv DES/AES-128, validation); 10 tests
- [x] **USM auth crypto (reference)** — `UsmSecurity` (pure, JDK-only) implements RFC 3414 §A.2
      password-to-key (1 MB-stream KDF, MD5 + SHA-1), §2.6 engine-ID key localization, and HMAC-96 auth
      parameters; verified against the RFC 3414 §A.3 published vectors (10 tests).
- [x] **SNMPv3/USM on the wire** — `SnmpService.getV3(cfg,host,oids…)`/`walkV3(cfg,host,root,maxRows)` open a
      v3 session via SNMP4J's native USM (local engine ID, `UsmUser`, `UserTarget`, `ScopedPDU`), reusing the
      shared varbind `decode`. Pure `SnmpV3Usm` mapper (auth MD5/SHA/SHA-256 → OID, priv DES/AES-128 → OID,
      security level → `SecurityLevel`, `toUsmUser`) is the offline-tested seam (12 tests; mapper + construction
      smoke). Existing v1/v2c `get`/`walk` untouched. **SnmpView now has a v3 tab:** "3" in the version
      selector reveals a USM panel (security level, user, auth proto+passphrase, priv proto+passphrase,
      context) and hides community; GET/WALK validate an `SnmpV3Config` and call `getV3`/`walkV3` (port-aware
      overloads added). _(live E2E against a real v3 agent still TODO)_
- [x] **Trap receiver** — `SnmpTrapReceiver` listens on UDP 162 (configurable/ephemeral) for v1/v2c
      traps, decodes trap OID (RFC 3584 for v1) + varbinds with `OidRegistry` name resolution; `SnmpView`
      **Traps** tab with start/stop, community filter, live table (7 tests incl. loopback round-trip)
- [-] **Inform receiver/ack** — **done:** `SnmpTrapReceiver` now acknowledges `PDU.INFORM`
      notifications by returning a `RESPONSE` PDU (echoes request-id, cleared error-status, dispatched on
      the arriving message-processing/security model+level+name) via `returnResponsePdu`; `Trap` gains an
      `inform` flag; loopback round-trip test asserts both delivery + a non-null RESPONSE to the sender
      (9 tests). **TODO:** real v3/USM auth+priv **on the wire** (model done)
- [-] **USM privacy crypto** — **done (offline core):** `UsmPrivacy` implements the two classic SNMPv3
      privacy protocols with `javax.crypto` only (no new deps): **DES-CBC** (RFC 3414 §8 — localized-key
      bytes 0–7 = DES key, 8–15 = pre-IV, salt = engineBoots‖localInt, IV = pre-IV XOR salt, zero-padded to
      8) and **AES-128-CFB** (RFC 3826 — 16-byte key, IV = engineBoots‖engineTime‖salt, stream/no-pad).
      Reuses `UsmSecurity` key localization; `Encrypted(ciphertext, privParameters)` result with defensive
      copies; 21 tests (RFC 3414 §A.3.1 localized key, round-trips, padding/length, wrong-salt corruption,
      frozen KAT vectors). **TODO:** wire priv into `SnmpService.getV3/walkV3` on the wire.

---

## PHASE 9 — MONITORING, METRICS & POLISH

### 9.1 Metrics Dashboard
- [x] `MetricsCollector` — thread-safe per-channel throughput / error-rate / latency aggregation;
      lifetime totals exact + bounded-window percentiles; pure nearest-rank `percentile` helper
      (`nexuslink-core/metrics`, **8/8 tests**); registered in `AppContext`, fed by the REST view via `Metrics`
- [x] `MetricsView` (Tools ▸ Metrics Dashboard…) — live per-channel table + requests/sec LineChart,
      1s `Timeline` refresh, Reset button; **P50/P95/P99** columns
- [-] Per-endpoint breakdown, exportable reports, alerting thresholds — **exportable reports + alerting
      done:** pure `MetricsReport` renders a collector snapshot to CSV (RFC 4180 quoting) or JSON
      (dependency-free), 5 tests, wired to an **Export…** button (FileChooser → CSV/JSON by extension);
      pure `MetricsAlerts` evaluates a snapshot against `Thresholds` (error-rate / P95 / mean, with a
      min-samples floor; each threshold individually disable-able), 6 tests, wired into the dashboard —
      breaching channels are tinted (`:alert` row pseudo-class) and counted in the status bar (⚠ N over
      threshold). _(per-endpoint breakdown beyond the existing per-channel grouping still TODO.)_
- [ ] Connection state panel (active/idle/failed counts)

### 9.2 Distributed Tracing
- [x] W3C Trace Context injection/parsing (`traceparent`, `tracestate`) — pure `TraceContext` (Level 1):
      `parseTraceparent`/`tryParseTraceparent` (validates field count, lowercase-hex, all-zero trace/parent-id,
      version-`00` trailing data, reserved `ff`), `formatTraceparent`, `newRootTraceparent`, `childOf`/`child`,
      `injectInto`/`extract`; nested immutable `TraceState` (OWS trimming, whole-list drop on invalid/dup/>32,
      `with(k,v)` move-to-front + evict). `SecureRandom` ids. 44 tests. **Auto-inject wired:** a **Trace**
      toggle in REST Settings (`RestRequest.traceEnabled`) makes `RestExecutionService` inject a fresh root
      `traceparent` on each request (respecting a user-supplied one), verified end-to-end vs a loopback server.
- [x] Jaeger/Zipkin span export — pure dependency-free `ZipkinSpanExporter` renders captured `Span`s
      (traceId/id/parentId/name/kind/timestamp-µs/duration-µs/localEndpoint/tags) to Zipkin v2 JSON (the
      format Jaeger's Zipkin collector also accepts); root vs child, kind/service/tags omitted when absent,
      sorted tags, full escaping (8 tests). **Wired end-to-end:** when tracing is on, `RestExecutionService`
      captures one CLIENT span per request (traceId/spanId from the sent `traceparent`, http.method/url/
      status tags) into a session buffer; a **Trace** button in the REST URL bar exports the buffer as a
      Zipkin `.json`. Service-level tests (4) verify inject + capture + user-traceparent passthrough + clear.
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
- [-] `RestCodeGenerator` — REST request → client snippet (per-language enum registry). _SPI across all protocols TODO._
- [x] Output languages: cURL, Python (requests), JavaScript (fetch), Java (HttpClient), PowerShell,
      **Node (axios), C# (HttpClient), Go (net/http), Rust (reqwest), PHP (curl), Ruby (net/http)** — 10 tests
- [x] `CodeGenDialog` — language dropdown (driven by `Language.values()`), copy button

### 9.6 Native Packaging
- [x] **Double-clickable uber JAR** — `nexuslink-app` `fatjar` profile: a non-`Application` `Main`
      launcher (so `java -jar` skips the "JavaFX runtime components are missing" error) + maven-shade
      (Main-Class `com.nexuslink.app.Main`, `ServicesResourceTransformer` keeps the SPIs) + JavaFX
      native classifiers for win/mac/mac-aarch64 alongside the host's. One `target/nexuslink.jar` runs
      on Windows/macOS/Linux (needs Java 21+). `mvn -Pfatjar -pl nexuslink-app -am clean package`. See PACKAGING.md.
- [x] **`jpackage` self-contained app-image** — `jpackage` profile (panteleyev plugin) stages the fat
      jar and bundles a Java runtime into a native app needing NO Java installed (`target/dist/NexusLink/`,
      run `bin/NexusLink`). `mvn -Pfatjar,jpackage -pl nexuslink-app -am clean verify`. Built per-OS;
      switch `<type>` to EXE/MSI · DMG/PKG · DEB/RPM for installers (may need extra OS tooling).
- [ ] `jlink` — further minimize the bundled runtime (jpackage currently bundles the full JDK runtime)
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
- 2026-07-02: **File Commander — pause/resume + bandwidth throttle.** New JavaFX-free `TransferGovernor`
  governs the in-flight transfer at the one shared choke point (the cumulative-bytes progress callback,
  called per 64 KB chunk): it blocks the transfer thread while paused and sleeps to cap the average rate —
  so no SFTP/FTP/local copy code changed. `TransferQueue` exposes `pause`/`resume`/`isPaused`/
  `setMaxBytesPerSecond`; `stopWorker` resumes so a paused transfer can wind down. `TransferQueuePanel`
  footer gains a Pause/Resume button + a Limit combo (Unlimited/512 KB/1/5/10 MB/s). Injectable clock +
  sleeper → deterministic throttle tests; real-thread pause test. +5 tests. Full `mvn test` BUILD SUCCESS.
- 2026-07-02: **JSON highlighting across API testers.** Extended `ui.util.JsonView` with a content-aware
  `plainArea`/`setSmart` (highlights only when the text starts with `{`/`[`, so XML/hex/error bodies stay
  plain) and wired it into the **REST response body** (all view modes), **gRPC** response, and **GraphQL**
  response panes — each now a themed RichTextFX `CodeArea` in a `VirtualizedScrollPane`. Closes the REST
  "RichTextFX syntax highlight TODO". +2 JsonView tests (5 total). Full `mvn test` BUILD SUCCESS.
- 2026-07-02: **UX polish pass — professional look.** (1) **Coloured HTTP verbs** everywhere via new
  `ui.util.HttpMethods` (green/amber/blue/violet/red `.method-*` classes, previously dead): REST method
  combo (button + dropdown cells) and History rows (leading verb → coloured pill). (2) **JSON syntax
  highlighting** via new `ui.util.JsonView` (RichTextFX `CodeArea`, themed `.json-*` classes) — Mongo
  result pane + editable document dialog now colour keys/strings/numbers/bool/null. (3) **ER-diagram table
  picker** — `JdbcService.erDiagramMermaid(tables)` + checkbox dialog in `SqlClientView` (dangling FKs to
  excluded tables dropped). (4) **Diagram Export SVG + PNG** buttons in `DiagramView` (SVG from the DOM,
  PNG via `snapshot`). Added `javafx-swing` + `richtextfx` deps to nexuslink-ui. New tests: HttpMethods (3),
  JsonView (3), ER filter (2). Full `mvn test` BUILD SUCCESS; fat-jar rebuilt (278 MB, richtextfx shaded,
  launches clean).
- 2026-06-29: **Session 41 — REST HMAC auth (REST depth, offline-testable signer).**
  - `HmacAuthenticator` (`nexuslink-protocol-http`): generic shared-secret request signer for the many
    bespoke "HMAC a canonical string" schemes APIs roll themselves. Builds a *string-to-sign* from a
    template over `{method} {path} {query} {url} {host} {date} {body} {body-sha256-hex|base64} {keyId}`
    (with `\n` escape for line-oriented forms), HMACs it (HmacSHA256/SHA1/SHA512), encodes hex or base64,
    and renders the header from a second template (`{signature} {keyId} …`). Emits a `Date` header when the
    signed string uses `{date}` so the value is verifiable server-side. Pure/side-effect-free.
  - Verified offline against the **RFC 4231 §4.3 HMAC-SHA256 known-answer vector** (key `Jefe`) plus
    template/encoding/Date-emission behaviour — **5/5 tests** (90 in the http module).
  - Wired through: `RestRequest` (new `HMAC` AuthType + fields + `${VAR}` interpolation), `RestExecutionService`
    (signs and adds headers before send), `RestClientView` (full Auth-tab fieldset: algorithm/key-id/secret/
    encoding/string-to-sign/header-name/header-value + placeholder hint, visibility-gated, tab-state persisted
    minus the secret), and `RestCodeGenerator` (best-effort header note). Full `mvn test` green across all 22 modules.
- 2026-06-28: **Session 39 — SQL/JDBC TLS, SFTP/FTP file commander, and 5 parallel protocol-depth streams.**
  - **SQL/JDBC TLS** (`protocol-db`): new driver-agnostic `SslMode` + `JdbcTlsSpec` → `JdbcTlsParams`
    maps generic TLS material to each driver's own params — PostgreSQL/CockroachDB
    (`ssl`/`sslmode`/`sslrootcert`/`sslcert`/`sslkey`, NonValidatingFactory for trust-all), MySQL
    (`sslMode` + `*KeyStoreUrl`), MariaDB (`sslMode`/`serverSslCert`/`trustStore`/`keyStore`), SQL Server
    (`encrypt`/`trustServerCertificate`). `JdbcService.connect` now takes extra props; SQL view has a
    collapsible **TLS / SSL** pane (SSL-mode combo, CA/trust-store + client cert/key-store + trust-all,
    PEM-vs-keystore auto-sorted by extension). **11 new tests**, all offline.
  - **SFTP/FTP file commander** (WinSCP/MobaXterm/Tectia-style): `SftpService`/`FtpService` gained
    upload/download (with progress), mkdir, rename, delete (recursive), and SFTP chmod. New reusable
    `com.nexuslink.ui.files` package — `FileSystem`/`FileTransfer` adapters, `LocalFileSystem`,
    `FileBrowserPane` (address bar, Up/Refresh/New-Folder, details table, context menu, F2/Del keys),
    and `DualPaneBrowser` (local↔remote split with Upload →/← Download + progress bar). SFTP & FTP views
    rewritten around it (connect → two-pane commander, with Disconnect). **Natural mouse UX:** cross-pane
    **drag-and-drop** (drag local→remote uploads, remote→local downloads) with a drop-target highlight,
    plus Ctrl/Shift multi-select.
  - **5 parallel worktree-isolated agents** (one module each, offline-testable), merged by extracting
    only their new files (existing module poms preserved):
    1. **REST code-gen** — 6 new client targets (Node/axios, C#, Go, Rust, PHP, Ruby). _10 tests._
    2. **REST cookie jar + response assertions** — RFC 6265 `CookieJar`, declarative `ResponseAssertions`
       (status/header/body/JSON-path). _39 tests._
    3. **LDAP** — `LdifWriter`/`LdifReader` (RFC 2849), `Dn`/`Rdn` (RFC 4514), `LdapEntry`. _29 tests._
    4. **RabbitMQ** — `RabbitMqManagementClient` (HTTP management API: overview/queues/exchanges/bindings/
       purge, vhost encoding, Basic auth, JSON→records) + `DeadLetterArgs` (DLX) builder. _24 tests._
    5. **SNMP** — `OidRegistry` (OID↔MIB name, longest-prefix + instance suffix) + `SnmpV3Config` USM
       model; SNMP view shows symbolic names + accepts them as input. _23 tests._
  - **VERIFIED:** full `mvn test` **BUILD SUCCESS** across all 22 modules. _Lesson: worktrees fork from the
    branch point, so agents told a module "doesn't exist" had simply forked behind main — merge by
    cherry-picking their new files, not their from-scratch poms._
- 2026-06-27: **Session 38 — TLS/mTLS extended to WebSocket, gRPC, and Kafka (user asked to parallelize).**
  - Attempted 3 parallel fork agents (worktree-isolated, one per protocol) — a transient **server-side
    rate limit** cut them off ~32 min in; only the gRPC backend (`GrpcService` netty SslContext + pom)
    was salvageable. Completed all three integrations directly instead.
  - **WebSocket** (`WebSocketService`/`WebSocketView`): `wss://` builds a dedicated `HttpClient` with a
    custom `SSLContext` from `TlsContextFactory`; collapsible TLS/mTLS pane (trust store + client key
    store + trust-all).
  - **gRPC** (`GrpcService`/`GrpcView`): TLS targets build a netty client `SslContext` via
    `GrpcSslContexts` from the trust store (`TrustManagerFactory`) and/or client key store
    (`KeyManagerFactory`), or `InsecureTrustManagerFactory` for trust-all; `protocol-grpc` now depends on
    `nexuslink-security`. TLS material section shown when TLS is on.
  - **Kafka** (`KafkaView`): for SSL/SASL_SSL, `securityProps()` emits `ssl.truststore.*`,
    `ssl.keystore.*` + `ssl.key.password`, and optional empty `ssl.endpoint.identification.algorithm`
    (skip hostname check). Backend already accepted an ssl.* prop map.
  - All reuse the REST client's trust-store/client-key-store UX. **VERIFIED:** full `mvn clean install`
    **BUILD SUCCESS** (22 modules). _Lesson: spawning many concurrent Opus agents tripped a server rate limit._
- 2026-06-27: **Session 37 — Certificate Bundle Builder + TLS/mTLS connection material (user-requested).**
  - **Certificate Bundle Builder** (`CertificateBundleDialog`, cert manager **Build Bundle…**): pick certs
    from the store, order them leaf→root, choose a target format — **full-chain PEM** (web servers),
    **PKCS#12 with private key** (Java/Windows/client mTLS), **CA trust bundle (PEM)**, or **CA trust
    store (PKCS#12)** — each with live on-screen guidance about where it's used. Reuses `CertificateExporter`.
  - **TLS / mTLS material** (`nexuslink-security/tls`): `TlsConfig` (trust store + key store + trust-all,
    JKS/PKCS12 autodetect) and `TlsContextFactory.create` → an `SSLContext` with a key manager (client
    cert for mutual TLS) and/or trust manager (CAs to trust) or trust-all. **6/6 tests**, including **real
    loopback TLS and mTLS handshakes** + an untrusted-server rejection — no live server needed.
  - Wired into the **REST** client: a **Settings ▸ TLS / mTLS** section (trust-store / client-key-store
    Browse… + passwords + trust-all checkbox, `${VAR}`-resolved, non-secret paths persisted);
    `RestExecutionService` builds the `HttpClient` with the custom `SSLContext` when configured.
    `protocol-http` now depends on `nexuslink-security` (acyclic). New **TLS & Mutual TLS** + updated
    **Certificate Manager** help topics explain how to get the files and point a connection at them.
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 22 modules); `TlsContextFactoryTest` 6/6.
- 2026-06-27: **Session 36 — Monitoring metrics dashboard (Phase 9.1, offline-testable aggregation).**
  - `MetricsCollector` (`nexuslink-core/metrics`): thread-safe per-channel request metrics — exact
    lifetime count/errors/bytes/min/max/mean + latency **P50/P95/P99** over a bounded recent-sample
    window + requests/sec over a trailing window. Pure nearest-rank `percentile` helper. **8/8 tests.**
  - Registered in `AppContext`; the REST view records each response (latency/success/bytes) via a small
    `ui.metrics.Metrics` helper (no-op when unregistered). `MetricsView` (**Tools ▸ Metrics Dashboard…**)
    shows a live per-channel table + a requests/sec LineChart, refreshed by a 1s `Timeline`, with Reset.
    New **Metrics Dashboard** help topic.
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 22 modules); `MetricsCollectorTest` 8/8.
- 2026-06-27: **Session 35 — Certificate-manager §1.2 polish (export/import/CSR, all offline round-trips).**
  - `CertificateExporter` (`nexuslink-security/cert`): export a cert as **DER**, a **concatenated PEM
    bundle**, or a **password-protected PKCS#12** keystore (key + chain, or a cert-only trust store).
  - `CertificateImporter`: load **PKCS#12 / JKS bundles** — every alias decoded to its leaf cert, full
    chain, and private key (when present); `typeForFileName` autodetects JKS vs PKCS12.
  - `CertificateGenerator.generateCsr`: generate a key pair + **PKCS#10 CSR** (PEM), SANs carried in a
    requested-extensions attribute — what you send a CA to be issued a real certificate.
  - **7/7 round-trip tests** (`CertificateExportImportTest`), all offline: DER/PEM re-parse to the same
    cert, PKCS#12 re-loads its key + chain, wrong-password rejected, CSR is well-formed PEM for the subject.
  - `CertificateManagerView` wired: **Export…** (PEM/DER/PKCS12 chosen by extension, password-prompted for
    p12), **Import Bundle…** (adds every keystore entry), and **Generate CSR…** (saves the CSR + matching
    private-key PEM).
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 22 modules); security module 18/18.
- 2026-06-27: **Session 34 — REST AWS SigV4 + Digest auth (REST depth, offline-testable signers).**
  - `AwsSigV4Signer` (`nexuslink-protocol-http`): full AWS Signature v4 (canonical request → string-to-sign
    → date/region/service/`aws4_request` signing-key chain → `Authorization` header), plus `X-Amz-Date`
    and `X-Amz-Security-Token` for temporary credentials. Verified against the official
    **`aws-sig-v4-test-suite` "get-vanilla"** known-answer vector (signature
    `5fa00fa3…fbf31`) + a session-token case. **2/2 tests.**
  - `DigestAuthenticator` (`nexuslink-protocol-http`): parse a `WWW-Authenticate: Digest` challenge and
    compute the `Authorization: Digest` response (`qop=auth`, MD5; legacy RFC 2069 fallback). Verified
    against the canonical **RFC 2617 §3.5** known-answer (`6629fae4…c4ef1`). **4/4 tests.**
  - Wired into `RestExecutionService`: SigV4 signs and adds headers before send; **Digest** does the
    challenge-response — first request unauthenticated, then on a 401 Digest challenge compute + retry
    once. Refactored request building into a reusable `buildRequest(req, digestHeader)` helper.
  - `RestRequest` gained `AuthType.AWS_SIGV4` + `AuthType.DIGEST` and the AWS fields (region/service/
    access-key/secret-key/session-token; Digest reuses username/password), threaded through
    `interpolated()`. `RestClientView` Auth tab shows the matching rows; JSON save persists non-secret
    AWS fields (secret/session re-entered, like the Basic password).
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 22 modules); http module 21/21 incl.
    the two new known-answer suites. Live calls need a real AWS endpoint / Digest server.
- 2026-06-27: **Session 33 — SNMP browser (Phase 8.6), second directory-services protocol.**
  - New module **`nexuslink-protocol-snmp`** (reactor now 22 modules): `SnmpService` over SNMP4J —
    open a community **v1/v2c** UDP session and **GET** an OID or **WALK** a subtree (GETNEXT loop with
    end-of-MIB / out-of-subtree / non-advancing-agent guards), returning decoded varbinds (OID dotted
    string, SMI type, value).
  - **Offline-testable** per the user's steer: the decision logic is factored into pure helpers —
    `versionOf` (text→SNMP4J constant), `normalizeAddress` (`udp:host/port`), `isValidOid`, and
    `toVarBind` (decode a `VariableBinding`, constructable without a socket) — and unit-tested **4/4**;
    live GET/WALK needs a reachable agent.
  - **`SnmpView`** — open bar (host/port/community/version), an OID field with **GET**/**WALK** buttons,
    and an OID/type/value results table; `${VAR}` resolved in host/community/OID; client-side OID
    validation before querying. Wired to **File ▸ New SNMP Browser** + sidebar (the `snmp` help topic
    already existed).
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 22 modules); `SnmpServiceTest` 4/4.
- 2026-06-27: **Session 32 — REST OAuth 2.0 Authorization Code + PKCE (REST depth, offline-testable).**
  - New `OAuth2AuthorizationCode` (`nexuslink-protocol-http`): the pure flow pieces — PKCE pair generation,
    `S256` challenge derivation (base64url(sha256), unpadded), authorization-URL construction (preserving
    existing query params, URL-encoding), redirect-callback parsing (code/state/error), and token-response
    parsing — plus a live `exchangeCode` (HTTP Basic for confidential clients, `client_id`-only for public
    PKCE clients). **8/8 tests**, including the **RFC 7636 Appendix-B known-answer vector**.
  - Interactive **`OAuth2AuthCodeDialog`** (`nexuslink-ui/rest`): fill endpoints/client → **Open
    authorization URL** (generates PKCE + state, launches the browser) → approve → paste the redirect
    URL → **Exchange for token** (state-mismatch/CSRF guard) → returns the access token, which the REST
    client applies as a **Bearer** credential. Reached from a button in the REST Auth tab's OAuth 2.0
    section (prefilled from the existing token-URL/client fields); `${VAR}` resolved throughout.
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 21 modules); `OAuth2AuthorizationCodeTest`
    8/8. The token exchange needs a live OAuth provider; the crypto/URL/parse logic is fully offline-tested.
- 2026-06-27: **Session 31 — LDAP / Active Directory client (Phase 8.4), opens directory services.**
  - New module **`nexuslink-protocol-ldap`** (reactor now 21 modules): `LdapService` over the UnboundID
    LDAP SDK — connect plain or **LDAPS** (trust-all, testing tool) with an optional bind DN+password,
    read the Root-DSE **naming contexts**, and run **base/one/sub** searches with an RFC-4515 filter
    and size limit, returning DN + decoded attributes. `scopeOf` is a pure helper.
  - **Tested end-to-end with no external LDAP**: UnboundID bundles an `InMemoryDirectoryServer`, so the
    suite seeds `dc=example,dc=com` with people and asserts real connect/bind/search behaviour —
    subtree count, filter narrowing + attribute decode, base-scope single entry, anonymous bind, and
    not-connected rejection. **6/6 tests.**
  - **`LdapView`** — connect bar (host/port/bind DN/password/LDAPS toggle that flips 389↔636), search
    bar (base/filter/scope/limit), DN result list + attribute-detail pane; naming contexts pre-fill the
    base on connect; `${VAR}` resolved in every field. Wired to **File ▸ New LDAP Browser** + sidebar
    (the `ldap` help topic already existed).
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 21 modules); `LdapServiceTest` 6/6.
    Live AD/LDAP browse needs a directory server.
- 2026-06-26: **Session 30 — `ProfileValidator` (completes Phase 1) + the MCP→Agent tool-calling loop.**
  - **`ProfileValidator`** (`nexuslink-core/connection`) — pure, per-protocol pre-save validation: required
    name; protocol-specific target shape (URL schemes for REST/WS/SSE/GraphQL/MQTT, `jdbc:` for SQL,
    `mongodb(+srv)://` for Mongo, host:port for gRPC/MQ + Kafka bootstrap, etc.); per-`AuthMethod`
    required fields (username for BASIC/SASL, token/key for Bearer/API-key/SigV4 accepting either raw
    or vaulted `*Ref` keys, keystore for mTLS, tokenUrl+clientId for OAuth2, principal for Kerberos,
    private key for SSH). Wired into `MainWindow.saveConnection`, which now blocks an invalid save
    with an Alert. **13/13 tests.** This was the last unchecked Phase-1 item → **Phase 1 complete.**
  - **MCP → Agent tool-calling loop** (the "agent testing" endgame): new `com.nexuslink.protocol.ai.agent.McpAgentRunner`
    hands an MCP server's tools to Claude via the Anthropic Java SDK and runs the full loop — model
    emits `tool_use` → runner executes via an injected `ToolExecutor` (wired to `McpClient.callTool`)
    → feeds `tool_result` blocks back → repeats until the model answers (12-turn cap). Assistant
    `Message`s are appended whole so thinking + tool_use blocks survive between turns. The pure
    `toAnthropicTool` seam (MCP JSON-Schema → Anthropic `Tool.InputSchema`) is unit-tested **3/3**
    without an API key. New **`AgentView`** (Tools/File ▸ New AI Agent) connects an MCP server (HTTP/
    stdio), shows the tool count, and streams turns/tool-calls/results into a live transcript; `${VAR}`
    resolved in target/token/system/task. New **AI Agent** help topic. LLM tab relabeled "LLM".
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 20 modules); `ProfileValidatorTest`
    13/13, `McpAgentRunnerTest` 3/3. Live agent run needs `ANTHROPIC_API_KEY` + an MCP server.
- 2026-06-26: **Session 29 — RabbitMQ first cut (Phase 5.5), the #1 next-step item.**
  - New module **`nexuslink-protocol-rabbitmq`** (registered in the reactor, 20 modules now): `RabbitMqService`
    over the official `amqp-client` (AMQP 0.9.1) — connect via `amqp://`/`amqps://` URI or bare
    `host[:port]` + credentials, declare exchange (direct/fanout/topic/headers)/queue/binding, publish
    persistent messages, and consume a queue with auto-ack into a listener.
  - The connection-spec build is factored into a **pure `factoryFor` seam** so it is fully unit-tested
    without a live broker — host/port parsing, AMQP vs AMQPS default ports, URI-embedded credentials
    winning over explicit args, blank-field defaults, and blank-target rejection (**7/7 tests**).
  - **`RabbitMqView`** (Messaging) — declare exchange/queue/binding, publish to exchange + routing key,
    consume a queue into a live timestamped message log; `${VAR}` resolved in **every** field at
    connect/declare/publish/consume time. Wired into **File ▸ New RabbitMQ Client** + sidebar, with a
    new **RabbitMQ Client** help topic.
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 20 modules); `RabbitMqServiceTest` 7/7.
- 2026-06-26: **Session 28 — `${VAR}` adoption completed across every remaining protocol view.**
  - Extended the shared `com.nexuslink.ui.env.Env` helper to **gRPC** (host + request body), **SQL/JDBC**
    (URL/user/pass + the executed statement), **MongoDB** (connection string + query/pipeline),
    **Redis** (URI + console command), **Kafka** (bootstrap, SASL user/pass, produce + consume
    topics/key/value/group), **MQTT** (broker/clientId/user/pass + subscribe/publish topics + payload),
    **SFTP** + **FTP** (host + credentials), **S3** (endpoint/keys/region), **Azure** (connection
    string), **GCS** (project + credentials path), **MCP Inspector** (target, auth token, tool +
    prompt arguments), and the **LLM tester** (system + user prompt).
  - Every protocol view now resolves `${VAR}` against the active environment at send/connect time;
    `${VAR}` is a no-op when no environment is active. Phase-1.4 adoption follow-up is now **done**.
  - **VERIFIED:** full `mvn clean install` **BUILD SUCCESS** (all 19 modules); UI compiles clean.
- 2026-06-26: **Session 27 — `${VAR}` adoption in the HTTP-family views (applies the Phase-1.4 engine).**
  - `RestRequest.interpolated(UnaryOperator<String>)` — returns a deep copy with every string field
    (URL, query params, headers, body, all auth fields) resolved, **without mutating** the editor's
    bound model, so history/replay stay templated and re-resolve at replay time. Unit-tested (7/7).
  - `RestClientView.send()` now executes the interpolated copy against the active environment and
    **scrubs resolved secrets** from the logged URL via `EnvironmentService.masker()`.
  - New shared `com.nexuslink.ui.env.Env` helper (`service()` / `resolve()`) — no-op when no
    `EnvironmentService` is registered. Adopted in **WebSocket** + **SSE** (connect URL) and
    **GraphQL** (endpoint + query + variables); REST refactored to use it too.
  - **VERIFIED:** `RestRequestTest` 7/7; full `mvn test` **BUILD SUCCESS**; UI compiles clean.
  - Follow-up: thread the same through the remaining views (gRPC/SQL/Mongo/Redis/Kafka/MQTT/
    file/object-storage/MCP/LLM).
- 2026-06-26: **Session 26 — Environment-variable system (Phase 1.4, last unstarted Phase-1 foundation).**
  - New `com.nexuslink.core.env` package:
    - `Environment` / `EnvVariable` — a named variable set (dev/staging/prod) with per-var secret flags.
    - `EnvironmentService` — owns the environments + active selection, persisted to
      `~/.nexuslink/environments.json`; resolves `${VAR}` with precedence **active env → `.env` file
      (`~/.nexuslink/.env`) → system env**; convenience `interpolate`/`interpolateAll`/`resolver`/`masker`.
    - `VariableInterpolator` — `${VAR}`, `${VAR:-default}`, `$$` escape, nested refs with a cycle guard;
      unknown names stay literal so unresolved refs are visible.
    - `SecretMaskingFilter` — `looksSecret` name heuristic (auto-flags password/token/key/…), `scrub`
      strips secret values from arbitrary text (longest-first), `maskValue` for masked UI fields.
  - UI: `EnvironmentManagerView` tab (`nexuslink-ui/.../env`) — environment list with an active ●
    marker, an editable name/value/secret variable table, New/Delete/Set-Active, and a reveal-secrets
    toggle. Wired into **Tools ▸ Environments…**; `EnvironmentService` registered in `AppContext` so any
    view can interpolate. Rewrote `environment-vars.md` help from a roadmap stub into real usage docs.
  - **VERIFIED:** core env tests **18/18** (`VariableInterpolatorTest` 7, `EnvironmentServiceTest` 6,
    `SecretMaskingFilterTest` 5); full `mvn test` **BUILD SUCCESS**; UI compiles clean.
  - Follow-up left `[-]`: thread `${VAR}` through each protocol view's send path (engine + active env
    already available via `AppContext.resolve(EnvironmentService.class)`).
- 2026-06-26: **Session 25 — `ExpirationWatchdog` (finishes Phase 1.2 cert follow-ups, priority #1).**
  - `nexuslink-security/.../cert/ExpirationWatchdog` — periodically scans a `Supplier<Map<alias,
    CertificateInfo>>` and fires an `Alert` the first time each cert crosses a 30/7/1-day threshold
    (escalating, never re-firing the same stage) and again on expiry. `scan(Instant)` is clock-
    injectable and side-effect-free (returns the freshly-fired alerts) so it unit-tests without wall
    clock; `start(Duration)` runs a daemon scanner; listeners + `aliasesNeedingAttention()`.
  - **Fixed a real escalation bug found by the tests:** with thresholds sorted ascending `[1,7,30]`
    the original `stageFor` returned `i+1`, so creeping *toward* expiry produced a *lower* stage and
    the `stage <= previous` guard swallowed the escalation. Stages now map nearest-expiry → highest
    stage, so urgency increases monotonically.
  - Wired into `CertificateManagerView`: on every `refresh()` the watchdog re-scans the working store
    and shows a colour-coded status-bar summary (`⚠ N expiring soon, M expired — <most-urgent>`),
    amber for warnings / red when anything has expired, logging each alert through the view logger.
  - **VERIFIED:** `ExpirationWatchdogTest` **6/6**; security module **16/16**; full `mvn test`
    **BUILD SUCCESS**; UI compiles clean.
- 2026-06-25: **Session 24 — Certificate Manager (Phase 1.2, the second unstarted Phase-1 foundation).**
  - New `cert` package in `nexuslink-security` (BouncyCastle added to the module; both bcprov +
    bcpkix were already in `dependencyManagement`):
    - `CertificateInfo` — UI snapshot (subject/issuer/serial/validity/SANs/key+sig algo/CA flag/
      SHA-256) with a VALID / EXPIRING_SOON / EXPIRED / NOT_YET_VALID status (30-day window).
    - `CertificateParser` — X.509 decode from PEM or DER via the JDK `CertificateFactory`.
    - `CertificateGenerator` — self-signed RSA (2048/4096) / ECDSA (P-256/P-384) with configurable
      validity + SAN (DNS/IP), self-signature verified; PEM export of cert or private key.
    - `CertificateStore` — PKCS12/JKS keystore wrapper (load/save/list/import cert/import key+chain/
      get key+chain/export/delete), password-protected.
  - UI: `CertificateManagerView` — colour-coded alias list (green/amber/red by status) + a full
    X.509 details pane + Generate-Self-Signed dialog + PEM Import/Export + Delete + Save/Open
    keystore (key entries keep their private keys on save). Wired into **Tools ▸ Certificate
    Manager…** (opens a "Certificates" tab). Rewrote `certificate-manager.md` help from a roadmap
    note into real usage docs.
  - **VERIFIED:** `CertificateManagerTest` **5/5** (generate RSA+EC → parse round-trip, validity-
    window status, PEM round-trip, keystore persist/reload of key + trusted entries); security
    module 10/10; `-pl …-security,ui,app -am install` builds clean. Changes uncommitted.
  - Follow-ups (left `[-]` in §1.2): DER/PKCS12-with-password export, PKCS12/JKS bundle import +
    drag-and-drop, CSR generation. (`ExpirationWatchdog` done — Session 25.)
- 2026-06-25: **Session 23 — MQTT client (Phase 5 kickoff) + all tracking docs refreshed.**
  - **Refreshed every tracking markdown** to match reality (they had drifted ~10 sessions
    behind): `README.md`, `PROJECT_PLAN.md`, `docs/ARCHITECTURE.md`, `RUN.md`, and the
    `NexusLink_Specification.md` status banner now list the full built protocol set, the ~45%
    completion figure (112 done · 24 in-progress · 116 not started), and what remains.
  - **MQTT client** — new `nexuslink-protocol-mqtt` module (Eclipse Paho v3.1.1; the dep was
    already in `dependencyManagement`). `MqttService`: connect (tcp/ssl/ws, optional
    user/pass + Last-Will, auto-reconnect, MemoryPersistence), subscribe/unsubscribe (QoS
    0/1/2), publish (QoS + retained), streaming listener with connection-lost callback.
  - `MqttView` (UI): broker bar + connect/disconnect toggle, client-id/auth fields, a
    subscribe row (topic filter + QoS), a publish panel (topic/QoS/retained/payload), and a
    live timestamped message log. Wired into `MainWindow` (File menu + sidebar + openProfile
    `MQTT` case), added the `MQTT` protocol to the `ConnectionProfile` enum + icon hint, and
    added a **HiveMQ public-broker sample** to `SampleCatalog`. Rewrote the `mqtt.md` help
    topic from a roadmap note into real usage docs.
  - **VERIFIED LIVE vs. broker.hivemq.com:** headless round-trip through `MqttService` —
    connected, subscribed to a unique topic at QoS 1, published, and received the exact
    payload back, then disconnected cleanly. Full `-pl …-mqtt,core,ui,app -am install`
    builds clean. Changes uncommitted in the working tree.
- 2026-06-25: **Session 22 — MCP Bearer-token auth (the real remaining MCP gap).**
  - `HttpMcpTransport` already accepted an `extraHeaders` map but `McpInspectorView` always passed
    `Map.of()`, so the HTTP transport sent *no* credentials — connecting to auth-gated servers
    (Render and friends) was impossible.
  - Added a **Bearer token** `PasswordField` to the connect bar, shown only for the HTTP transport
    (hidden for stdio). On connect, `authHeaders()` turns it into an `Authorization` header: a bare
    token becomes `Bearer <token>`; a value that already carries a scheme (`Bearer`/`Basic`/`token`)
    is sent verbatim, so non-Bearer schemes still work. The header flows through to every request +
    notification via the existing `extraHeaders` plumbing.
  - **VERIFIED:** `nexuslink-protocol-ai,nexuslink-ui` compile clean; `McpClientTest` 5/5 green.
    Live Render verification + vaulting the token (currently field-only) + a full OAuth device/PKCE
    flow remain as follow-ups. Changes uncommitted in the working tree.
- 2026-06-25: **Session 21 — MCP Inspector fixes (tested against Render's hosted MCP server).**
  - **Status dot never went green when connected.** Root cause: `McpInspectorView.connect()` set `status-err` on failure but never reset the style on success, and `.meta-label` (declared later in `theme-base.css`) overrode `.status-ok` at equal specificity. Fix: connecting/ok/err each `setAll(meta-label + status-*)`, and added compound selectors (`.meta-label.status-ok` etc.) so the status colour wins regardless of source order. Added `.status-ok` / `.status-connecting` CSS.
  - **400 on post-handshake calls.** `HttpMcpTransport` never sent the `MCP-Protocol-Version` header that MCP rev. 2025-03-26+ requires after `initialize`; strict servers (Render) reply 400. Fix: `McpTransport.setProtocolVersion(...)` (default no-op), `HttpMcpTransport` sends the header on every request + notification, `McpClient.connect()` hands it the negotiated version before the initialized notification.
  - **"prompts not supported" errors.** `loadAll()` called `prompts/list` + `resources/list` unconditionally. Added `McpClient.serverSupports(capability)` and gated discovery on the advertised capabilities; unadvertised ones are skipped with a log line.
  - **Arguments (JSON) box too short / unreadable.** Long Render tool descriptions crowded it out. Gave `toolArgs` minHeight 140 / 10 rows and `toolResult` minHeight 120 / 8 rows; moved the description into a capped (≤90px) scrollable band (`.desc-scroll`).
  - **VERIFIED:** `nexuslink-protocol-ai,nexuslink-ui` compile clean; `McpClientTest` 5/5 green. **NOT yet verified live** against Render (user deferred to tomorrow). Changes uncommitted in working tree.
- 2026-06-25: **Session 20 — MongoDB power features (toward "beyond Studio 3T").**
  - **Schema diagram from Mongo** — `MongoService.inferDiagram` samples documents per collection,
    infers BSON field types, guesses relationships (`<name>_id`/`Id`) → Mermaid; rendered in the
    interactive `DiagramView`. Verified rendering via probe.
  - **SQL-like queries** — `MongoService.executeSql` (SELECT/WHERE/ORDER BY/LIMIT, = != > < >= <= LIKE)
    → `find()`; a `sql` operation mode beside `find` (JSON filter). Both options available.
    `MongoSqlTest` 6/6.
  - **Compass-like result views** — JSON / **Table** (flattened grid) / **Schema** (field → type(s) +
    count + % present) selector on query results.
  - **Export** results to JSON/CSV (`toJsonArray`/`toCsv`, tested) + **explain plan**.
  - **In-grid edit/delete** from the Table view (context menu + double-click; `replaceById`/`deleteById`).
  - **Visual aggregation pipeline builder** (add/remove stages → run or load into editor).
  - **Query history** — recall recent find/SQL/aggregate queries.
  - Full `mvn install` + `mvn test` clean (MongoSqlTest 8/8); boots clean. The Mongo client now spans
    explorer + find/SQL/aggregate/explain/CRUD + schema diagram + Compass views + export + pipeline
    builder + in-grid edit — aiming beyond Studio 3T.
- 2026-06-24: **Session 19 — FTP, per-user protocol visibility, interactive ER diagram, DB structure helpers.**
  - **FTP/FTPS** client (`nexuslink-protocol-ftp`, Apache Commons Net) — verified live vs. test.rebex.net.
  - **Per-user protocol enable/disable** — data-driven catalog + View ▸ Protocols… dialog, persisted
    (`ProtocolPrefs`), so each user shows only the connectors they use.
  - **Interactive ER diagram** (`DiagramView`) — Mermaid v11 in a WebView with **svg-pan-zoom**
    (mouse-wheel zoom + drag-pan) and a toolbar to toggle layout (TB/LR), theme, and fit/reset.
    Verified via standalone probe.
  - **DB structure helpers** — SQL: Create Table… / Create Index… (DDL builder + run + refresh);
    Mongo: Create Collection… / Create Index… (`MongoService.createCollection/createIndex`).
- 2026-06-24: **Session 18 — SFTP client.**
  - New `nexuslink-protocol-sftp` module (Apache MINA SSHD): `SftpService` (password + SSH-key auth,
    list dir, read file, permission strings) + `SftpExplorer` (lazy remote directory tree) + `SftpView`.
  - Wired into the shell; Rebex SFTP sample opens prefilled. **Verified live vs. test.rebex.net**
    (listed root, read readme.txt). Full `mvn install` + `mvn test` clean; boots clean.
- 2026-06-24: **Session 17 — gRPC client (dynamic, reflection-based).**
  - New `nexuslink-protocol-grpc` module (grpc-java + protobuf, versions aligned with the GCS SDK):
    `GrpcService` — connect (plaintext/TLS), list services/methods via **server reflection**
    (recursive descriptor resolution), and invoke **unary** methods with JSON ↔ `DynamicMessage`.
  - `GrpcView` — host/port/TLS bar, service + method pickers (streaming-flagged), JSON request editor,
    response panel. Wired into the shell; grpcb.in sample opens prefilled.
  - **VERIFIED LIVE vs. grpcb.in:** listed 4 services, detected unary/streaming methods, invoked
    `grpcbin.GRPCBin/Index` → real JSON response. Full `mvn install` + `mvn test` clean; boots clean.
- 2026-06-24: **Session 16 — Google Cloud Storage (object-storage trio complete).**
  - New `nexuslink-protocol-gcs` module (Google Cloud Storage SDK): `GcsService` (project +
    service-account JSON key / ADC; listBuckets, listObjects) + `GcsExplorer` + `GcsView` (project +
    key-file picker). GCS protocol + sample; wired into the shell.
  - **S3 + Azure Blob + GCS** now all share the one `ResourceExplorerView` BUCKET→OBJECT pattern.
  - Full `mvn install` + `mvn test` clean; boots clean with the (heavy) GCS SDK on the classpath.
    _Needs GCP credentials for E2E test._
- 2026-06-24: **Session 15 — Azure Blob Storage.**
  - New `nexuslink-protocol-azure` module (Azure SDK): `AzureBlobService` (connection string / shared
    key; listContainers, listBlobs) + `AzureBlobExplorer` (container → blob tree, reusing the S3-style
    BUCKET/OBJECT pattern) + `AzureBlobView`. Azurite local-emulator sample added.
  - Wired into the shell (menu/sidebar/profile case). Full `mvn install` + `mvn test` clean; boots
    clean with the Azure SDK on the classpath. _Needs an account/Azurite for E2E test._
- 2026-06-24: **Session 14 — Redis client + explorer lazy-details.**
  - New `nexuslink-protocol-redis` module (Lettuce): `RedisService` (connect, SCAN keys, typed value
    read for string/hash/list/set/zset/stream, ~20-command console runner) + `RedisExplorer`.
  - `RedisView`: `redis://` URI bar, key browser (value-on-select) + command console; wired into shell.
  - **Explorer enhancement:** `ResourceExplorerView` now fetches `explorer.details(node)` lazily in the
    background on selection (default still returns static details) — powers Redis value-on-select and is
    reusable by other explorers.
  - Full `mvn install` + `mvn test` clean; boots clean with Lettuce on the classpath. _Needs a live
    Redis for E2E test._
- 2026-06-24: **Session 13 — Kafka client (first cut).**
  - New `nexuslink-protocol-kafka` module: `KafkaService` (Admin topic discovery, lazy producer,
    background-poll consumer; bootstrap + security map for PLAINTEXT/SSL/SASL) + `KafkaExplorer`
    (topics → partitions with leader/replica/ISR details).
  - `KafkaView`: brokers + security bar (protocol/SASL mechanism/user/pass), topic explorer, and
    Produce / Consume tabs (send shows partition+offset; consume streams live records).
  - Wired into the shell (menu/sidebar; Kafka sample opens prefilled). Full `mvn install` + `mvn test`
    clean; app boots clean with kafka-clients on the classpath. **Live broker needed for E2E test.**
- 2026-06-24: **Session 12 — New protocols: SSE, GraphQL, S3 object storage.**
  - **SSE client** (`SseService` + `SseView`) over the JDK HTTP client — live-verified (162 events
    from the Wikimedia firehose).
  - **GraphQL client** (`GraphQLService` + `GraphQLView`) — query/variables/introspection;
    live-verified vs. countries.trevorblades.com.
  - **S3 / object-storage** — new `nexuslink-protocol-s3` module (AWS SDK v2, URL-connection client,
    path-style, S3-compatible). `S3Service` + `S3Explorer` (bucket→object tree) + `S3View`;
    `secretKey` added to the vaulted secret keys. **Live-verified: 647 buckets from MinIO Play.**
  - All three wired into the shell (menu/sidebar/samples). Full `mvn install` + `mvn test` clean;
    clean boot with the AWS SDK on the classpath.
- 2026-06-24: **Session 11 — Markdown/Mermaid help, ER diagrams, richer connections.**
  - **OAuth 2.0 client-credentials** for REST (`OAuth2TokenClient` with token caching/refresh).
  - **Save/open REST connections** with vault-backed auth (Basic/Bearer/API-key/OAuth2); generalized
    secret vaulting over password/token/apiKeyValue/clientSecret → `*Ref`.
  - **Markdown + Mermaid viewer** (`MarkdownView`: WebView + commonmark + mermaid.js); the Help dialog
    now renders topic content through it (verified by screenshot). Wired `-Dnexuslink.autohelp`.
  - **ER diagrams**: `JdbcService.erDiagramMermaid()` → Mermaid `erDiagram` from tables/PK/FK; "ER
    Diagram" button in the SQL view renders it. Unit-tested.
  - **Richer connection catalog**: added GRAPHQL/GRPC/SSE/REDIS/FTP/S3 protocols + `BUCKET`/`OBJECT`
    node kinds; new public samples — GitHub, SWAPI, Countries & Rick-and-Morty GraphQL, grpcb.in,
    Wikimedia SSE firehose, **MinIO Play S3 sandbox**, Rebex FTP, local Redis.
  - **VERIFIED:** full `mvn install` + `mvn test` BUILD SUCCESS (RestRequestTest 6, JdbcServiceTest 6);
    screenshots confirm Markdown help + the expanded Samples tree. Six commits pushed.
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

## NEXT ACTION  — RESUME POINT (saved 2026-07-05, after SQL-workbench + theming session)

> Trust `git log --oneline -20`, not this prose. Everything below is committed on `main` (local;
> not yet pushed as of this note).

**Landed this session (UI look-and-feel + SQL workbench depth):**
- `654e640` Sidebar Samples group opens **collapsed** (with count); **per-protocol accent theming**
  across every view via a `-nl-view-accent` indirection layer + `-nl-p-*` palette (both themes).
- `a654983` SQL editor overhaul — RichTextFX **syntax highlighting** (on the shared `SqlTokenizer`),
  Run-selection, **Format**, Ctrl+Space **autocomplete**, Ctrl+/ comment, multi-statement run with a
  **Messages** tab, typed two-line result headers, NULL styling, content-sized columns, rows/cols/ms strip.
- `b72cb9c` Dedicated **JSON syntax palette** (`-nl-json-*`) — keys/strings/numbers/bool/null distinct,
  shared by REST · Mongo · gRPC · GraphQL · Kafka · MCP.
- `cb7c579` **Structure export** — pick tables/views → portable `CREATE TABLE` DDL (cols, PK, FK,
  indexes, view summary) with Copy / Save. `SchemaExporter` (+tests), `JdbcService.exportSchema`.
- `e9a9cc8` **Richer schema tree** — category folders (Tables/Views/Procedures/Functions), per-table
  columns · indexes · foreign keys. New `JdbcService` metadata readers (+`JdbcExplorerTest`).
- `e14a046` Schema-tree **right-click actions** (Generate SELECT / View DDL / Copy / Drop) + a reusable
  **preview-SQL-then-apply** write gate (Drop uses it; UPDATE/DELETE/ALTER to follow).

**SQL workbench — data/structure editing + query help (DONE this session):**
- ✅ `1d767fa` row **DELETE**, `8f568c0` in-grid **UPDATE** — both through `previewAndApply`, gated on
  single-table + PK detection (`JdbcService.primaryKeyColumns`).
- ✅ `1e14a86` **structure editing** — ALTER add / rename / drop column (right-click table/column).
- ✅ `e861a71` **EXPLAIN plan** (dialect-aware) + themed **result charting** (bar chart).

**SQL workbench — still open:**
- **Visual query builder** (pick table/columns/filters → generated SQL); inline editor error markers;
  row insert (blank-row append → INSERT); multi-column / line charts.

**Then the broader roadmap** (verify each against `git log` first — the list below is from Session 40
and much has since landed: `TransferGovernor` throttling, REST NTLM/HMAC/Digest/OAuth, Kafka browser/metrics):

## NEXT ACTION  — RESUME POINT (saved 2026-06-29, after Session 40)

> ⚠️ **This section goes stale — trust `git log`, not this prose.** Sessions 40a–40c (REST timeline +
> assertions + cookie jar, RabbitMQ dashboard, LDAP DIT/LDIF) landed on `main` **without** this block
> being updated, which caused a parallel run to re-implement already-done work. Before planning, run
> `git log --oneline -30` and grep the tree for the files a task references.
>
> ⚠️ **Parallel/worktree agents:** isolated worktrees have spawned from an **old base commit**, not
> current `main`. Each agent must fast-forward its branch to `main` first (or work directly in the main
> checkout) and then verify `git log main..HEAD` is just its own commit before you integrate.

**Where the project stands:** Full `mvn test` is **BUILD SUCCESS** across all 22 modules (Mongo IT
Docker-gated via `-DrunMongoIT=true`). Working: shell + dark/light theming, help system,
**credential vault** (UI + auto-lock), **certificate manager** + bundle builder,
**environment-variable system**, history/settings/vault-backup, and protocol clients — REST
(+ **cookie jar**, **response assertions tab**, **waterfall timeline**, 6 code-gen languages),
WebSocket, SSE, GraphQL, gRPC, **SQL/JDBC (+ driver-specific TLS)**, MongoDB, Redis,
**Kafka** (consume table + payload formatter + JSON/CSV export), **MQTT**,
**RabbitMQ** (+ management **dashboard** with numeric-sort tables + overview strip + DLX builder),
**SFTP / FTP-FTPS** (WinSCP-style two-pane commander, drag-and-drop, **transfer queue + overwrite/skip
prompts**), S3/Azure/GCS, MCP Inspector, AI/LLM tester, the **AI Agent (MCP tool-calling loop)**,
**LDAP / AD** (browse/search + filter builder + entry CRUD + **live LDIF import** + **lazy DIT tree**),
and an **SNMP browser** (v1/v2c GET/WALK + MIB-name resolution + v3/USM config model + **trap receiver**).
TLS/mTLS covers REST, WebSocket, gRPC, Kafka, and SQL/JDBC.

### ✅ Tree state on resume

`git status` clean. **Push policy changed 2026-06-29:** `origin/main` is now kept in sync (was
previously held back) — push after each landed batch. Session 40 (parallel run) added, all committed
and **pushed** on `main`:
- `f0e7a39` RabbitMQ dashboard polish (overview strip + numeric-sort tables + refresh-all API)
- `60b97fd` LDAP live LDIF import (apply to server) + lazy DIT tree browser
- `a705eef` SNMP v1/v2c trap receiver + live trap table
- `302429c` File commander transfer queue panel + overwrite/skip prompts

### ⏭ Highest-value next steps (pick per priority, **offline-testable first** per user)

1. **File commander depth** — transfer **speed & ETA**, pause/resume/retry/cancel, reorder, throttle;
   **recursive directory** transfers; overwrite-if-newer / rename conflict modes; bookmarks/sessions;
   always-visible local pane before connect; path/breadcrumb bar; column sort; sync browsing.
2. **SNMP depth** — inform receiver/ack; real v3/USM auth+priv **on the wire** (model + traps done).
3. **Kafka deep-dive** (Phase 4, still largely open) — consumer-group lag monitor, schema registry
   client, message-browser filters (offset/timestamp/key/value/header), Kafka metrics.
4. **REST** — remaining auth (NTLM, HMAC, custom-script); pre-request script runner.

_(Done Session 40: parallel run — RabbitMQ dashboard polish · LDAP live LDIF + lazy DIT · SNMP trap
receiver · file-commander transfer queue. Earlier Sessions 40a–c had already added REST timeline +
assertions + cookie jar, the base RabbitMQ dashboard, and base LDAP DIT/LDIF.)_

### How to resume

```bash
cd /home/pratyush/software/nexuslink
git log --oneline -30            # TRUST THIS over the prose above; check what's actually landed
git status                       # should be clean
mvn -DskipTests install          # build all modules
mvn test                         # full suite (Mongo IT skipped without -DrunMongoIT=true)
cd nexuslink-app && mvn javafx:run   # launch the desktop app (needs a graphical display)
```

Then read this file top-to-bottom, scan the PROGRESS LOG (Sessions 24 → 1) for context, and
continue from the first `[ ]` in the active phase or the priority list above.

