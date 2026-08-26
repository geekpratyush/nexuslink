# NexusLink documentation

Start here — this page says which document answers which question.

| I want to… | Read |
|---|---|
| Understand what NexusLink is and see the feature status | [`../README.md`](../README.md) |
| Run it from source | [`../RUN.md`](../RUN.md) |
| Know how the code is laid out, and how to add a protocol | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Build a double-clickable JAR or a native app image | [`../PACKAGING.md`](../PACKAGING.md) |
| Publish it to my company's Artifactory, so users install with one command | [`../DISTRIBUTION.md`](../DISTRIBUTION.md) |
| Download a launcher, or read the public page | [`index.html`](index.html) — served by GitHub Pages from this folder |
| See what shipped and when | [`CHANGELOG.md`](CHANGELOG.md) |
| See what is planned and what is done | [`../TASKS.md`](../TASKS.md) and [`../.agent-os/specs/PROGRESS.md`](../.agent-os/specs/PROGRESS.md) |
| Read the original product specification | [`../NexusLink_Specification.md`](../NexusLink_Specification.md) |
| Run the live integration tests against real servers | [`../test-env/README.md`](../test-env/README.md) |

## In-app help

The documentation users actually see is the **Help browser** (**F1**), whose topics live in
`nexuslink-ui/src/main/resources/com/nexuslink/ui/help/` and are registered in `HelpService`. It is
searchable, and it is the right place for anything a user needs while the app is open:

| Topic | Covers |
|---|---|
| Getting Started | First run, the workspace, where to go next |
| **Menus & Toolbars** | Every menu, submenu and per-tab toolbar, and what each item does |
| REST Client | Requests, auth, body types, tests, extraction, collections, the runner |
| Database Clients | The SQL/JDBC workbench, drivers, dialect-correct generated SQL, Redis |
| **MongoDB Client** | Query bar, views, typed editing, shell, change streams, GridFS, compare |
| Kafka Client | Produce with headers/tombstones, consume, schema decoding, groups, cluster, config |
| MQTT · RabbitMQ · gRPC · GraphQL · SFTP · LDAP · SNMP | The other clients |
| Security & Authentication · TLS & mTLS · Certificate Manager | Credentials, certificates, the vault |
| Environment Variables · Code Generation · Metrics · Plugins | Cross-cutting features |
| **Installing & Updating** | The Artifactory bootstrap, for users who never see the source |
| Keyboard Shortcuts · Troubleshooting | Reference |

When you add or change a feature, update its help topic in the same commit — the help browser is the
only documentation most users will ever open.

## A note on accuracy

These documents describe what the code does today, not what is planned. Roadmap items live in
`TASKS.md` and the specs; anything written here as working has been run. Where a feature has a real
limitation it is stated in place rather than omitted.

## This folder is also the website

`docs/` doubles as the GitHub Pages site (Settings ▸ Pages ▸ *Deploy from a branch* ▸ `/docs`):

- `.nojekyll` disables Jekyll so every file is served as-is.
- `index.html` is self-contained — no external CSS or JS, and it follows the reader's light/dark
  preference.
- `downloads/` holds the launcher scripts the page offers. They are copies; `dist/` is canonical, and
  `dist/publish.sh` refreshes them on every run.
