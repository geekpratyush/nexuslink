# Plugin Development

NexusLink is built around a small plugin API so new protocols slot in cleanly.

## Key interfaces (`nexuslink-plugin-api`)
- **`ProtocolConnector`** — the SPI a protocol implements (validate config, connect, return a result).
- **`ConnectionConfig`** — a protocol-agnostic config bag with vault references.
- **`ResourceExplorer`** / **`ResourceNode`** — expose a connected resource's object hierarchy (server → database → table/collection/topic/bucket → …) as a lazily-loaded tree with a details panel.

## How a protocol is structured
1. A `nexuslink-protocol-*` module wraps the client library and exposes a small service (connect + operations) — e.g. `JdbcService`, `MongoService`, `KafkaService`.
2. An explorer maps that service to `ResourceNode`s (e.g. `JdbcExplorer`, `MongoExplorer`).
3. A `*View` in `nexuslink-ui` provides the tab UI and reuses `ResourceExplorerView` for the tree.
4. The view is wired into `MainWindow` (menu + sidebar) — and can be hidden per user via **View ▸ Protocols…**.

See `docs/ARCHITECTURE.md` in the repository for the full "add a protocol" checklist.
