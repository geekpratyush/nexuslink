# Tech stack

| Layer | Choice |
|-------|--------|
| Language | Java 21 (runtime target; 17+ language baseline) |
| UI | JavaFX 21+, CSS themes, RichTextFX for editors |
| Build | Maven 3.9+, multi-module reactor |
| JSON | Jackson (`jackson-databind`) |
| Persistence | SQLite (history), AES-256-GCM JSON (vault, profiles) |
| Cache | Caffeine (in-memory) |
| DB pooling | HikariCP |
| Tests | JUnit 5, Mockito; gated `*LiveIT` against `test-env/` Docker |

## Adding a dependency

The bar is high, and it is highest for anything on the request path.

1. **Can the JDK do it?** `java.net.http`, `java.util.prefs`, `ServiceLoader` and `java.sql` already
   cover most of what gets proposed. Several deliberate hand-rolled implementations exist here —
   RFC 7578 multipart, HAR 1.2 export, MD4 for NTLM — because a dependency was not worth it.
2. **Does it fit the offline constraint?** A library that phones home, or that only resolves from a
   repository the user's org doesn't mirror, is disqualified.
3. **What is its licence?** Anything shipped in the app must be permissive (Apache/EPL/MIT/BSD).
   Heavy or restrictively licensed artifacts — Oracle's driver, DB2's, Simba's BigQuery driver —
   are **catalogued and loaded on demand**, never bundled. See `product/decisions.md` #9.

## Network calls: the house rules

Any outbound HTTP the app makes itself must:

- go through `ProxySelector.getDefault()`, so JVM and system proxy settings are honoured;
- support authentication that is *configurable*, not assumed (API key, bearer, OIDC);
- allow the target host to be overridden — never hard-code a vendor's public hostname as the only
  option;
- fail with a message naming the URL tried and the setting that changes it.

`ExternalDriverLoader.download`, `HttpLlmClient` and `OidcTokenSource` are the reference
implementations.

## Filesystem layout

Everything the app owns lives under `~/.nexuslink/`:

| File | Contents |
|------|----------|
| `history.db` | request history (SQLite + FTS5) |
| `vault.json` | credentials, AES-256-GCM |
| `connections.json` | saved profiles, secrets as vault references |
| `environments.json` | `${VAR}` environment sets |
| `drivers/` | downloaded/attached JDBC driver jars |
| `user-drivers.json` | drivers the user added from their filesystem |
| `maven.properties` | optional internal Maven repository settings |
| `llm-endpoints.json` | configured LLM endpoints (no plaintext secrets) |
| `settings.json` | portable preference overlay |
