# Spec progress tracker

One place to see how far each spec has got. **The checkboxes live in the specs themselves** — this
table is the rollup. Update it in the same commit that ticks a spec item, and keep the counts honest:
count the boxes, do not estimate.

Opened 2026-08-25. Phases 0–9 in `TASKS.md` are complete and are **not** counted here; this is
forward work only (`TASKS.md` §10).

| Spec | Prefix | P1 | P2 | P3 | Done | Status |
|------|--------|----|----|----|------|--------|
| [REST ↔ Postman](2026-08-25-rest-postman-parity/) | — | 5 | 6 | 3 | **all 5 P1** | P1 complete |
| [SQL ↔ SQL Developer](2026-08-25-sql-developer-parity/) | — | 5 | 6 | 4 | all 5 P1 | P1 complete |
| [SQL beyond parity](2026-08-25-sql-beyond-parity/) | `SQLX-` | 8 editor + 6 flagship | — | — | 0 | not started |
| [MongoDB ↔ Compass / Studio 3T](2026-08-25-mongo-compass-studio3t-parity/) | `MG-` | 7 | 8 | 3 | **all 7 P1 + all 8 P2** | P1+P2 complete |
| [File transfer ↔ FileZilla / WinSCP](2026-08-25-file-transfer-filezilla-parity/) | `FX-` | 6 | 7 | 3 | 0 | not started |
| [IBM MQ ↔ MQ Explorer / Nastel](2026-08-25-ibmmq-beyond-nastel/) | `MQ-` | 7 | 7 | 3 | 0 | not started |
| [Kafka ↔ AKHQ / Conduktor](2026-08-25-kafka-management-parity/) | `KF-` | 7 | 8 | 3 | 6 of 7 P1 (all but KF-5 serdes) | in progress |
| [Remaining clients](2026-08-25-remaining-clients-parity/) | `MSG-` | ~20 grouped per protocol | — | — | 0 | not started |
| [Driver management](2026-08-25-driver-management/) | — | — | — | — | shipped | ✅ complete |
| [LLM endpoints](2026-08-25-llm-endpoints/) | — | — | — | — | shipped | ✅ complete |

## Shared foundations (`TASKS.md` §10.0) — build before the per-client work

| Foundation | Consumers | Status |
|------------|-----------|--------|
| Environment-governed destructive actions (`SQLX-2`) | SQL, Mongo `MG-14`, Kafka, Redis `MSG-R3`, RabbitMQ, IBM MQ | not started |
| DLQ browse + replay-with-preview dialog | `MQ-3`, `MSG-J4`, `MSG-C1`, `KF-7` | not started |
| Sampling + charting + threshold alerts | `MQ-8`, `KF-14`, `MSG-RD3`, `MSG-M4`, `MSG-SN3` | not started |
| Saved-request / session tree with folders | REST ✅ (`CollectionNode`), `FX-2`, `MSG-G3` | partial |
| Diff-then-apply for configuration | `KF-1`, `MQ-15`, `MSG-R1` (`ConfigDiff`/`SchemaDiff` exist) | partial |

## How to work a spec

1. Read the spec's **"Already built"** table — it was audited against the source, but re-grep before
   trusting it if the date is old. Never report status from session notes.
2. Take the next item in that spec's **Build order**, not the first unchecked box.
3. Pure logic first, in the protocol module, with tests; then the thin JavaFX wiring. See
   `../standards/code-style.md` and `../standards/testing.md`.
4. Tick the box in the spec, update this table, and note it in `TASKS.md` §10's rollup if a P1 list
   just emptied.
