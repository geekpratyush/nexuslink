# Database Clients

NexusLink ships SQL (JDBC), MongoDB, and Redis clients — all with a browsable object tree.

## SQL (JDBC)
- Pick a database from the dropdown (SQLite, PostgreSQL, MySQL, MariaDB and H2 are bundled; Oracle / SQL Server / DB2 etc. load on demand via **Load Driver…**).
- Enter a JDBC URL + optional user/password and **Connect**.
- The left **Schema** explorer shows database → tables/views → columns. Double-click a table to `SELECT * … LIMIT 100`.
- Run SQL with `Ctrl+Enter`.
- **ER Diagram** — generates an entity-relationship diagram (tables, PK/FK) you can zoom, pan, and flip between top-down / left-right.
- **Structure** — Create Table… / Create Index… with a form-driven DDL builder.

## MongoDB
- Connect with a `mongodb://` or `mongodb+srv://` string. The tree shows databases → collections → indexes (with collStats).
- **Operations:** `find` (JSON filter), **`sql`** (`SELECT … FROM coll WHERE … ORDER BY … LIMIT n`), `aggregate`, `explain`, and insert/update/delete.
- **Views:** switch results between **JSON**, **Table** (flattened grid), and **Schema** (field → type(s) + % present).
- **Diagram** — infers a schema diagram from sampled documents.
- **Pipeline…** — build an aggregation stage by stage.
- **Export** — save results as JSON or CSV.
- Edit or delete a document directly from the Table view (right-click or double-click).

## Redis
- Connect with a `redis://` / `rediss://` URI. Browse keys (value preview on select) and run commands from the console (`GET`, `SET`, `HGETALL`, `LRANGE`, `KEYS`, …).
