# Decisions

Architectural decisions and the reasoning behind them. Numbering continues the Decisions Log in
`TASKS.md`; entries added here are the ones an agent needs *before* writing code, because each one
looks wrong until you know the constraint that produced it.

## #9 — Bundle light drivers, load heavy ones on demand

**Decision.** Ship SQLite, H2, PostgreSQL, MySQL and MariaDB in the app. Catalogue Oracle, SQL
Server, DB2, Snowflake, ClickHouse, Redshift and BigQuery as on-demand, loaded from a jar at
runtime.

**Why.** Licensing (Oracle's OTN terms, DB2, Simba's proprietary BigQuery driver) and size. The
DBeaver/DataGrip model. JDBC's `ServiceLoader` SPI means a dropped-in jar needs no code change.

**Consequence.** `DriverShim` exists because `DriverManager` ignores drivers loaded by a child
classloader, and `JdbcConnectionPool` must never set `driverClassName` on the Hikari config or
pooled connections would bypass the shim.

## #14 — Driver installation must not depend on internet access

**Decision.** A driver can be installed three ways: direct download (configurable repository), a
`mvn` command the user runs in their own shell, or attaching a jar from disk. Jars already in
`~/.nexuslink/drivers` or `~/.m2/repository` are picked up automatically.

**Why.** The original implementation hard-coded `repo1.maven.org` with a bare `HttpClient`. In an
organisation that mirrors Maven through Artifactory and blocks direct egress — the target
audience — on-demand drivers could *never* be installed. The app's own network stack cannot
reliably reproduce a company's mirror, credentials, proxy and private CA; the user's Maven already
has all four. Handing over the exact command is more reliable than guessing.

**Consequence.** `MavenCommandHelp` must generate per-shell commands (bash / PowerShell / cmd) with
correct quoting, and a newly attached driver must register without a restart.

## #15 — Never write a plaintext secret to disk

**Decision.** Configuration files persist secrets only as `${ENV_VAR}` references. A literal secret
typed into a dialog lives in memory for the session and is dropped on save, and the UI says so
before the user types it.

**Why.** The alternative — a credential sitting in a world-readable JSON file in the home
directory — is a worse default than making the user re-enter it. Applies to
`llm-endpoints.json`; `connections.json` uses vault references for the same reason.

**Consequence.** A view holding user-entered config must keep its own session copy, because what the
store returns after saving is deliberately not what the user typed.

## #16 — SQL parameters follow SQL Developer semantics exactly

**Decision.** `:name` is a JDBC bind parameter; `&name` is a textual substitution; `&&name` is a
substitution remembered for the session.

**Why.** Users arrive with queries written for SQL Developer. Silently treating `&table_name` as a
bind would break `SELECT * FROM &table_name`, which is most of why substitution variables exist;
silently treating `:id` as a substitution would turn a bound value into SQL text and reintroduce
injection. The distinction is load-bearing and is surfaced in the prompt UI.

**Consequence.** Detection runs over `SqlTokenizer` output, never a regex, so a `:` inside a string
literal or comment is not a parameter.

## #17 — An outbound endpoint is always configurable

**Decision.** Any feature that calls a remote service exposes its base URL, auth method (including
OIDC client-credentials) and headers as configuration, with the vendor default as *a* choice rather
than the only one.

**Why.** In the target environment the vendor's public hostname is usually unreachable, and access
is federated through the company IdP. Applied to LLM endpoints (`LlmEndpointConfig`) and Maven
repositories (`MavenRepositoryConfig`).

**Consequence.** Request bodies must omit parameters the user did not set, rather than sending
defaults — strict internal gateways reject unknown or null fields.
