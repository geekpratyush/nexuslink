# Menus and toolbars — what everything does

Every menu, every item, and the toolbars inside each client tab. Shortcuts shown as **Ctrl** are
**Cmd** on macOS.

---

## Menu bar

### File

Built from the connection types you have enabled, so it changes with **View ▸ Protocols…**. Each
item opens a new tab of that kind:

| Item | Opens |
|---|---|
| New REST Request (**Ctrl+T**) | HTTP client — methods, params, headers, auth, body, tests |
| New WebSocket / New SSE Stream | Long-lived connections with a message log |
| New GraphQL Query | Query/mutation editor with schema-aware assist |
| New gRPC Client | Reflection or `.proto`-driven service calls |
| New SQL Client | JDBC workbench — editor, schema tree, results grid |
| New MongoDB Client | Query bar, tree view, shell, change streams, GridFS |
| New S3 / Azure Blob / Google Cloud Storage | Object-store commander |
| New SFTP / FTP Browser | Two-pane file transfer |
| New Kafka / MQTT / RabbitMQ / JMS / IBM MQ / Solace / SQS / Pub-Sub / Service Bus | Messaging clients |
| New Redis Client | Key browser and console |
| New LDAP Browser / SNMP Browser / SSH Terminal | Directory, network and shell tools |
| New MCP Inspector / AI Agent / AI LLM Tester | AI tooling |
| **Quit** | Closes NexusLink |

### Edit

Clipboard actions routed to whatever text control has focus — they work in every editor, grid cell
and form field.

| Item | Shortcut |
|---|---|
| Undo / Redo | **Ctrl+Z** / **Ctrl+Shift+Z** |
| Cut / Copy / Paste | **Ctrl+X** / **Ctrl+C** / **Ctrl+V** |
| Select All | **Ctrl+A** |

### View

| Item | What it does |
|---|---|
| **Toggle Log Panel** (**Ctrl+`**) | Shows or hides the Activity panel at the bottom — your own actions plus any warnings the protocol drivers raise |
| **Protocols…** | Tick which connection types appear in the File menu and the sidebar. Unticked ones disappear from both; nothing is uninstalled |
| **Toggle Theme** (**Ctrl+Shift+T**) | Switches light ↔ dark. The choice is remembered |

### Connection

The saved-connection actions, also reachable from the sidebar.

| Item | What it does |
|---|---|
| **Search Connections** (**Ctrl+K**) | Puts the caret in the sidebar's search box |
| **Open Selected Connection** | Opens the connection highlighted in the tree, as double-clicking does |
| **Import Connections…** | Reads a passphrase-encrypted bundle exported from another machine |
| **Export Connections…** | Writes your saved connections to an encrypted bundle to share with a teammate |
| **Restore Hidden Samples** | Brings back sample connections you have hidden |

### Tools

| Item | What it does |
|---|---|
| **Unlock Vault…** / **Lock Vault** | The encrypted store for passwords and tokens. Locked means secrets cannot be read, even by a saved connection |
| **Certificate Manager…** | Inspect PEM/PKCS12/JKS certificates, see expiry dates and chains, and build truststores |
| **Environments…** | Named sets of `${VAR}` values (dev / staging / prod) shared by every protocol tab |
| **Metrics Dashboard…** | Throughput, latency percentiles and error rates for the current session |
| **Connection State…** | Which connections are open, idle or failed, across every tab |
| **Secret Vaults…** | External secret sources (HashiCorp Vault, CyberArk Conjur, cloud secret managers) that `${VAR}` can resolve against |
| **Preferences…** (**Ctrl+,**) | Application settings |

### AI

| Item | What it does |
|---|---|
| **MCP Inspector** | Speak Model Context Protocol to a server — list and call tools, read resources, fetch prompts |
| **Agent / LLM Tester** | Send prompts to a model endpoint and inspect the response, tokens and tool calls |

### Help

| Item | What it does |
|---|---|
| **Help Index** (**F1**) | This help browser, with full-text search |
| **Keyboard Shortcuts** | The shortcut reference |
| **Welcome Tour** | The first-run walkthrough, any time you want it again |

---

## The left sidebar

**Search connections…** filters your Saved connections and the public Samples together as you type.
It matches a connection's name, protocol, host and user, so `kafka`, `prod kafka`, `5432` and
initials like `asb` all find what you would expect. The best match is selected as you type — press
**Enter** to open it, **↓** to step into the list, **Esc** to clear. **Ctrl+K** focuses the box from
anywhere.

**Filter connection types…** narrows the buttons below it. It knows the everyday word for each thing
rather than only the button label: `postgres` finds the SQL client, `queue` finds every broker,
`bucket` finds S3 / GCS / Azure Blob, `ai` finds the LLM and MCP tools. **Enter** opens the top match.

Right-click a saved connection for **Open**, **Delete** (or **Hide this sample**); right-click the
Samples group for **Restore hidden samples**.

---

## Inside a tab

### The connection chip

Every client shows its connection as a compact chip — the saved connection's name, or a short
`host:port/database` label — rather than a full-width text box. **Double-click** it (or press the ✎
button) to edit the real string; **Enter** commits and connects, **Esc** cancels. Credentials inside
a connection string are hidden while collapsed, and the right-click menu offers **Copy (credentials
hidden)** as well as **Copy including credentials**.

### REST client

- **Params / Headers** — key-value tables; untick a row to disable it without deleting it.
- **Body** — None / JSON / XML / Text / Form URL-encoded / **Form Data** (a parts table with a file
  picker per row) / **Binary** (send a file's bytes as-is).
- **Auth** — Basic, Bearer, API key, OAuth 2.0 (client-credentials and auth-code with PKCE), AWS
  SigV4, Digest, HMAC, NTLM. Tokens and keys are masked with a reveal toggle.
- **Pre-request Script** — `set VAR = …` lines with `now()`, `uuid()`, `base64()`, `hmacSha256()`.
- **Tests** — assertions on status, headers, body and JSON paths; results appear in **Test Results**.
- **Extract** — pull a value out of the response into a `${variable}` for the next request: JSON
  path, header, regex capture, status or whole body. This is what makes login-then-call chains work.
- **Settings** — timeouts, redirects, cookie jar, TLS/mTLS.
- Response pane: **Body** (Pretty / Raw / Hex, with **Save to file…**), **Headers**, **Cookies**,
  **Timeline** (a waterfall of request phases), **Trace**, **Test Results**.
- **Collections** — a request tree with folders and drag-to-reorder. Right-click a folder ▸
  **Run this folder…** for the collection runner: iterations, a CSV/JSON data file, delay,
  stop-on-failure, and a pass/fail report per request.
- Toolbar: **Send** (**Ctrl+Enter**), **Save**, **Import cURL**, **`</>`** code generation, **HAR**
  export, **Trace** export.

### SQL client

- Toolbar: **Database** picker, **Drivers…** (install or add a JDBC driver), connection chip,
  **Connect**, **Query Builder…**, **ER Diagram**, **Structure ▸** (Create Table / Create Index /
  **Run Procedure…** / Export Structure), **Code…**.
- Schema tree: right-click any object for **Create new ▸**, **Alter / Modify ▸** and **Drop…**, plus
  Generate SELECT on a table and **Run…** on a procedure. Selecting a routine shows its signature,
  parameters and **source** in the collapsible Source pane.
- Results: **Filter rows**, **Find** (walks to matches without hiding rows), **Insert row…**,
  **Import CSV…**, **Export…** (CSV / JSON / INSERT statements / XML / HTML / delimited-with-options,
  and the whole table rather than the page on screen). Right-click a row to copy it as INSERT,
  Markdown or CSV; right-click a column header to summarise it or **freeze** columns.
- Tabs beside the grid: **Messages**, **Server Output** (Oracle `DBMS_OUTPUT` and database notices),
  **History** (every statement run in this tab, with Recall and Recall & Run).
- Everything generated respects the connected engine: row caps are `LIMIT`, `FETCH FIRST … ROWS
  ONLY` or `TOP (n)`, identifiers quote correctly, and renames use `sp_rename` on SQL Server.
- Any statement that writes shows the exact SQL first; DROP, TRUNCATE and DELETE also require ticking
  a confirmation box.

### MongoDB client

See the **MongoDB Client** help topic — query bar, tree/JSON/table/schema/index views, the shell,
change streams and GridFS each have their own section there.

### Kafka client

- **Produce** — topic, key, value, plus a target **partition**, an explicit **timestamp**, a
  collapsible **headers** editor, and a **Tombstone** toggle that sends a null value (what deletes a
  key on a compacted topic).
- **Consume** — start/stop a consumer, or **Browse** a topic without joining a consumer group. A
  **Decode with Schema Registry** toggle renders Avro and JSON Schema payloads as JSON. Headers show
  in their own column; a tombstone is labelled rather than shown as an empty record. Right-click ▸
  **Show headers…** or **Replay selected to another topic…**.
- **Consumer Lag** — per-partition lag for a group, with offset reset preview and apply.
- **Groups** — members, client ids, hosts and assignments; delete a group or its offsets for a topic.
- **Cluster** — brokers, ports, racks and which one is the controller.
- **Topic Config** — effective settings with defaults marked; edit, preview the diff, apply.
- **Schema Registry** — subjects, versions, compatibility.

### File transfer and object stores (SFTP, FTP, S3, Azure Blob, GCS)

A two-pane commander: local disk on the left, the remote service on the right. Drag between panes,
queue transfers, quick-view a file, compare directories, batch rename, and set permissions where the
protocol supports it.

### Messaging clients (MQTT, RabbitMQ, JMS, IBM MQ, Solace, SQS/SNS, Pub/Sub, Service Bus)

Each has a connection bar, a browse/subscribe view with a message log, and a publish/send panel.
Payload formatting (String / JSON / Hex / Base64) is chosen per view.

### Redis client

Key browser with SCAN, typed value rendering, pub/sub, and a console that sends **any** command the
server supports — the server decides what it accepts, so module commands like `JSON.SET` and
`FT.SEARCH` work when the server has them.

---

## Activity panel

**Log** shows your actions and any warnings raised by the protocol drivers themselves (a failed SASL
handshake, an unavailable leader, a connection reset). **History** lists every request across every
protocol, with replay.
