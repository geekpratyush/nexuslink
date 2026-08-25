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
- Connect with a `mongodb://` or `mongodb+srv://` string. The tree shows databases → collections → indexes (with collStats).
- **Operations:** `find` (JSON filter), **`sql`** (`SELECT … FROM coll WHERE … ORDER BY … LIMIT n`), `aggregate`, `explain`, and insert/update/delete.
- **Views:** switch results between **JSON**, **Table** (flattened grid), and **Schema** (field → type(s) + % present).
- **Diagram** — infers a schema diagram from sampled documents.
- **Pipeline…** — build an aggregation stage by stage.
- **Export** — save results as JSON or CSV.
- Edit or delete a document directly from the Table view (right-click or double-click).

## Redis
The Redis client ships with its driver (Lettuce) built in at a fixed version — there is nothing to install, and Redis does not appear in the JDBC driver manager because it speaks its own wire protocol rather than JDBC. The same is true of MongoDB.

- Connect with a `redis://` / `rediss://` URI. Browse keys (value preview on select) and run commands from the console (`GET`, `SET`, `HGETALL`, `LRANGE`, `KEYS`, …).
