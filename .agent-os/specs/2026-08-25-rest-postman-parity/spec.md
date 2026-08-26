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
2. ~~**Collection runner.**~~ **DONE 2026-08-26.** Pure `RunPlan` (iterations, data rows — *the row
   count wins over the iteration count*, stop-on-failure, delay, CSV/JSON parsing), `RunReport` and
   `CollectionRunner` (assertions decide pass/fail, extracted values thread into the next request,
   cancel mid-run). `RestRequestJson` rebuilds a stored request with `${var}` substitution — the
   reading half the editor never had. UI: **Run this folder…** in the collections tree opens a runner
   with iterations, delay, data file, live result table and report. 22 unit tests + 7 against a real
   loopback HTTP server.
3. ~~**Form-data body UI.**~~ **DONE 2026-08-26.** `RestRequest.BodyType` gained `FORM_DATA` with a
   `FormPart` model (text or file, per-part content type, enable toggle); the Body tab shows a parts
   table with a file picker; `RestExecutionService` encodes it through the existing RFC 7578 encoder
   and takes the boundary-carrying Content-Type from it. Verified against a real server.
4. ~~**Post-response extraction.**~~ **DONE 2026-08-26.** Pure `ResponseExtraction` — JSON path
   (pointer or dotted), header, regex capture, status or whole body → a named variable, with a missing
   value reported rather than thrown. New **Extract** tab with a "test on the last response" preview;
   values publish into a **session-scoped runtime layer** in `EnvironmentService` that `${var}`
   consults first and that is never written to disk (a captured token belongs to this session).
   12 tests.
5. ~~**Binary / file request body** and **save response to file**.~~ **DONE 2026-08-26.**
   `BodyType.BINARY` sends a file's bytes as-is with a declarable content type (verified byte-for-byte
   against a real server); the response Body tab gained **Save to file…**, writing the bytes as
   received with a file name guessed from the URL and content type.

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
