# Database Clients

NexusLink ships SQL (JDBC), MongoDB, and Redis clients — all with a browsable object tree.

## SQL (JDBC)
- Pick a database from the dropdown (SQLite, PostgreSQL, MySQL, MariaDB and H2 are bundled; Oracle / SQL Server / DB2 etc. install on demand — see **Drivers** below).
- Enter a JDBC URL + optional user/password and **Connect**. The lamp next to the status line turns green while the connection is open; that one connection is reused for every statement.
- The left **Schema** explorer shows database → tables/views → columns. Double-click a table to `SELECT * … LIMIT 100`.
- Run SQL with `Ctrl+Enter`.
- **ER Diagram** — generates an entity-relationship diagram (tables, PK/FK) you can zoom, pan, and flip between top-down / left-right.
- **Structure** — Create Table… / Create Index… with a form-driven DDL builder.
- **Form view** — right-click a result row → *Open in form view…* to see one record per screen, one labelled field per column, with Previous/Next. On a single-table `SELECT` that includes the primary key the fields are editable and **Save** generates the `UPDATE` for review.

### The SQL it generates is the SQL your database accepts
Row caps, quoting, renames and more are not portable, so everything NexusLink writes for you follows
the connected engine:

| | Oracle | SQL Server | MySQL | SQLite |
|---|---|---|---|---|
| Browse a table | `FETCH FIRST 100 ROWS ONLY` | `SELECT TOP (100)` | `LIMIT 100` | `LIMIT 100` |
| Quoting | `"x"` | `[x]` | `` `x` `` | `"x"` |
| Empty a table | `TRUNCATE TABLE` | `TRUNCATE TABLE` | `TRUNCATE TABLE` | `DELETE FROM` |
| Rename a column | `ALTER … RENAME COLUMN` | `EXEC sp_rename …, 'COLUMN'` | `ALTER … RENAME COLUMN` | `ALTER … RENAME COLUMN` |
| Add a column | `ADD (col type)` | `ADD col type` | `ADD COLUMN col type` | `ADD COLUMN col type` |
| Replace a view | `CREATE OR REPLACE VIEW` | `CREATE OR ALTER VIEW` | `CREATE OR REPLACE VIEW` | `CREATE VIEW` |

Db2 gets `FETCH FIRST … ROWS ONLY` and the mandatory `TRUNCATE … IMMEDIATE`. (Oracle row-capping uses
the 12c syntax; on 11g and older you would need `ROWNUM`.)

### Working with objects
Right-click anything in the schema tree for the same three families of action — **Create new ▸**,
**Alter / Modify ▸**, **Drop…** — plus what suits that kind:

- **Table / view** — Generate SELECT, View/export DDL, Add column, Rename, Truncate, Drop.
- **Procedure / function** — **Run…** (a parameter form built from the database's own metadata, with
  IN/OUT direction and type per row), **Open source in the editor**, Drop. Selecting a routine shows
  its signature, its parameters and its **source** in the collapsible **Source** pane under the
  details table.
- **Column** — Rename, Drop, copy the name, insert it into the editor.
- **A category folder** — create a new object of that kind, or refresh.

Every statement that writes is shown to you first. DROP, TRUNCATE and DELETE also say plainly that
the change is permanent and keep **Apply** disabled until you tick the confirmation box.

### Results grid
- **Filter rows** hides non-matching rows; **Find** (▲ ▼) walks to the next match and selects it
  without hiding anything.
- **Summarise:** under the grid reports a column's count, nulls, distinct values, and sum/avg/min/max
  when it is numeric — following the current sort and filter.
- Right-click a **column header** to summarise it or **freeze columns up to** it; frozen columns stay
  put while the rest scrolls.
- Right-click a **row** to copy it as an INSERT, or the selection as Markdown or CSV.
- **Export…** writes CSV, JSON, **INSERT statements**, XML, HTML, or a delimited file with your own
  separator, quoting, header and NULL text — and can export the **whole table** rather than the page
  on screen.
- **Import CSV…** loads a file into the table behind the result, with column mapping.

### Beside the grid
- **Messages** — statement output and errors.
- **Server Output** — what the database printed: Oracle `DBMS_OUTPUT` (switched on for you) or the
  notices other engines raise. Drained after every execution.
- **History** — every statement run in this tab, with **Recall** and **Recall & Run**.

### Bind and substitution variables
Queries use the same conventions as Oracle SQL Developer, and NexusLink prompts for the values when you run them:

- `:name` — a **bind variable**. The value is sent to the database as a real JDBC parameter, so the SQL text is never rewritten with your input.
- `&name` — a **substitution variable**. The text is pasted into the statement before it runs, which is what makes `SELECT * FROM &table_name` work.
- `&&name` — the same, but the value is remembered for the rest of the session instead of prompting again.

Values inside string literals and comments are left alone, so `'mailto:someone'` is never mistaken for a parameter.

### Drivers
**Drivers…** opens the driver manager: every driver NexusLink knows about, with a lamp showing whether it is ready to use. There are three ways to install one.

1. **Try direct download** — works when this machine can reach Maven Central, or an internal repository you have configured.
2. **Get it with your own Maven** — pick your shell (Linux/macOS, PowerShell, or Command Prompt) and copy the `mvn` command shown. Run it in that terminal: Maven uses the settings your organisation already gave you in `~/.m2/settings.xml`, so the jar comes from your internal Artifactory/Nexus with your credentials and proxy. Then click **Add from JAR…** and select the downloaded file.
3. **Add from JAR…** — attach any driver jar already on the machine. NexusLink reads the driver class out of the jar, so usually you just confirm the name.

A driver added this way registers immediately — there is no need to restart. It is listed as *added by you* and can be removed again with **Remove** (the jar file itself is never deleted).

If the app can reach the internet only through an internal mirror, you can also point the direct download at it by setting `nexuslink.maven.repoUrl` (with `.username`/`.password` or `.token`), the `NEXUSLINK_MAVEN_*` environment variables, or `repoUrl` in `~/.nexuslink/maven.properties`. A mirror already configured in `~/.m2/settings.xml` is picked up automatically.

## MongoDB

The Mongo client has grown its own topic — see **MongoDB Client** for the query bar, the tree/JSON/
table/schema/index views, typed in-place editing, the `mongosh`-style shell, change streams, GridFS,
collection compare, the pipeline preview and the server panel.

## Redis
The Redis client ships with its driver (Lettuce) built in at a fixed version — there is nothing to install, and Redis does not appear in the JDBC driver manager because it speaks its own wire protocol rather than JDBC. The same is true of MongoDB.

- Connect with a `redis://` / `rediss://` URI, a `redis-sentinel://…#master` URI, or NexusLink's
  `redis-cluster://h1:6379,h2:6379` seed list. Browse keys (value preview on select).
- The console sends **any** command the server supports — it is dispatched as typed rather than
  matched against a list, so `GETDEL`, `OBJECT ENCODING`, `MEMORY USAGE`, `FUNCTION`, and module
  commands like `JSON.SET` or `FT.SEARCH` all work when the server has them. An unknown command comes
  back as the server's own error, and when the cause is a version gap it says so: *"GETDEL needs
  Redis 6.2; this server reports 6.0.16"*.
- Quoting follows `redis-cli`: `SET greeting "hello world"` sets one value with a space in it.
- The status line names the product and version — `Valkey 8.0.1 · cluster`, `Redis 7.2.4 ·
  standalone` — because a fork or an older server accepts a different command set.
