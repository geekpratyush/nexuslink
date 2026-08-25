# Spec — REST client: Postman parity and beyond

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §3.1

Audit of the REST client against Postman, based on the code as of 2026-08-25.

## Already at or beyond parity

| Capability | Where |
|------------|-------|
| All methods, params, headers, settings (timeouts, redirects) | `RestClientView`, `RestExecutionService` |
| Auth: Basic, Bearer, API key (header/query), OAuth2 client-credentials, OAuth2 auth-code **with PKCE** | `OAuth2TokenClient`, `OAuth2AuthorizationCode` |
| Auth: **AWS SigV4, Digest, HMAC, NTLM** — all verified against published test vectors | `AwsSigV4Signer`, `DigestAuthenticator`, `HmacAuthenticator`, `NtlmAuthenticator` |
| TLS / mTLS with client keystore | `TLS / mTLS` pane |
| Body: none, JSON, XML, text, form-urlencoded (+ RFC 7578 multipart encoder) | `MultipartFormData` |
| Pre-request script | `PreRequestScript` |
| Tests/assertions with pass/fail panel, persisted through history replay | `AssertionSpec`, `ResponseAssertions` |
| Response: pretty/raw/**hex**, JSON highlighting, headers, cookie jar | `BodyFormatter`, `CookieJar`, `JsonView` |
| **Timeline waterfall** of request phases | `TimelineView` |
| Code generation: cURL, Python, JavaScript, Java, PowerShell | `RestCodeGenerator` |
| cURL import | `CurlImporter` |
| `${VAR}` environments, request history with replay | shared with every protocol |
| **Beyond Postman:** HAR 1.2 export of a whole session; Zipkin/W3C trace export; four enterprise auth schemes built in rather than via plugins; and twenty other protocols — Kafka, MQ, gRPC, SQL, LDAP, SSH, S3 — in the same tool with the same vault and history | `HarExporter`, `ZipkinSpanExporter` |

## Gaps, prioritised

### P1 — the ones users will notice first

1. **Collections and folders.** There is no request tree: requests are saved as connection profiles,
   flat. Needs a collection tree with folders, drag to reorder, and collection-level variables and
   auth that a request inherits. This is the single biggest structural gap.
2. **Collection runner.** Run a folder in order, N iterations, optionally driven by a CSV/JSON data
   file, with a pass/fail report per request. The assertion engine already exists; the driver
   around it does not.
3. **Form-data body UI.** The RFC 7578 encoder is written and tested; the Body tab still cannot add
   a file part per row. Finish the wiring.
4. **Post-response scripts** / value extraction — capture a JSON path from a response into an
   environment variable so the next request can use it. Without this, chaining a login to a call
   is manual.
5. **Binary / file request body** and **save response to file**.

### P2

6. **OpenAPI / Swagger import** — generate a collection from a spec. High value in an org where
   every service publishes one.
7. **Postman collection import/export** (v2.1) — the realistic migration path for a team switching.
8. **Cookie manager UI** — the jar is captured and displayed but not editable.
9. **Bulk edit** of headers and params as text.
10. **Response diff** between two runs of the same request.
11. **Visualizer** for response payloads (table/chart rendering of a JSON body).

### P3

12. **Proxy capture / interceptor** for recording traffic from another application.
13. **Mock server** from a collection.
14. **CLI runner** (Newman equivalent) for CI.

## Excluded by the mission

- **Monitors / scheduled runs** — the mission's non-goals exclude scheduling.
- **Cloud workspaces and team sync as a hosted service.** Team collaboration stays file-based
  (export/import, shared git-tracked files); see `TASKS.md` §9.3.
