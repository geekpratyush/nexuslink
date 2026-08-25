# Roadmap

`TASKS.md` holds the detail and the checkboxes. This is the altitude view: which phase is closed,
what is genuinely next, and why.

## Phase status

| Phase | Area | Status |
|-------|------|--------|
| 0 | Scaffold | complete |
| 1 | Vault, certs, profiles, environments, history | complete |
| 2 | Help system | complete |
| 3 | HTTP core (REST/WS/SSE) | complete, with the Postman gaps in `specs/2026-08-25-rest-postman-parity` |
| 4 | Kafka | complete |
| 5 | Enterprise messaging (JMS, MQ, Solace, MQTT, RabbitMQ, cloud) | complete |
| 6 | Advanced HTTP (gRPC, GraphQL, incl. streaming) | complete |
| 7 | File transfer + object storage | complete |
| 8 | Database & enterprise protocols | complete through §8.6; SQL client gaps in `specs/2026-08-25-sql-developer-parity` |
| 9 | Monitoring, metrics, tracing, vaults, packaging | complete through §9.4; §9.5 LLM endpoints shipped 2026-08-25 |

## Phase 10 — best-in-class parity (opened 2026-08-25)

Phases 0–9 built the thirteen protocol clients. Phase 10 is about each of them being the *best* tool
for its protocol, not merely a working one. Six specs, each an audit of the real source plus a
prioritised gap list with stable item ids: Mongo (Compass/Studio 3T), SQL (beyond every client),
file transfer (FileZilla/WinSCP), IBM MQ (MQ Explorer/Nastel), Kafka (AKHQ/Conduktor), and one
covering every remaining client. Tracked in `../specs/PROGRESS.md` and `TASKS.md` §10.

**Build the five shared foundations first** (§10.0) — governed destructive actions, the DLQ replay
dialog, sampling+charting+alerts, the saved-request tree, and diff-then-apply config. Each is used by
four or five clients; building them per-client is how thirteen clients become thirteen codebases.

## What's next, in order

The parity specs are the backlog. The near-term P1 items, ranked across them:

1. **REST collections and folders** — the biggest structural gap against Postman, and a prerequisite
   for the collection runner.
2. **Collection runner** over the existing assertion engine.
3. **Stored procedure execution** with an IN/OUT parameter form.
4. **`DBMS_OUTPUT` / server output panel.**
5. **Form-data body UI** — the encoder is already written and tested.
6. **Post-response value extraction** into environment variables (request chaining).
7. **Richer SQL export formats** (INSERT statements, XML, HTML, delimited options).

Then Phase 10, starting with §10.0's governed execution, `SQLX-1` (DML undo with blast-radius
preview — the highest value-per-effort item in the whole backlog), and the IBM MQ PCF layer (`MQ-5`),
since the MQ client is the furthest of the thirteen from its parity target.

## Standing constraint

Every item above is judged against the mission's environment: it has to work with no direct internet
access, federated credentials, and no privileged installation. A feature that only works on an
open network is not done.
