# REST Client

Send HTTP/HTTPS requests over HTTP/2 and inspect responses.

## Sending a request

1. Pick a method (GET, POST, …) and type a URL in the bar.
2. Press **Send** (or `Ctrl+Enter`).
3. The response panel shows a colour-coded status, timing (total / TTFB / download), size, HTTP
   version, and the body (JSON is auto–pretty-printed).

`${VAR}` references anywhere in the URL, headers or body resolve against the active environment.

## Request tabs

- **Params** — query parameters (auto URL-encoded; a new row is added as you type). Untick a row to
  disable it without deleting it.
- **Headers** — request headers, same table behaviour.
- **Body**
  - `NONE` / `JSON` / `XML` / `TEXT` / `FORM_URLENCODED` — a text editor, with a *Format JSON* button.
  - `FORM_DATA` — a parts table: each row is a **Text** field or a **File** part, with an optional
    per-part content type. *Choose file for selected row…* picks the file; the request is encoded as
    RFC 7578 multipart with the boundary set for you.
  - `BINARY` — send a file's bytes exactly as they are, with the content type you declare. Use this
    for uploads that are not form posts.
- **Auth**
  - **Basic** — username / password
  - **Bearer** — a token (masked, with a reveal toggle)
  - **API Key** — key name + value, sent in a **header** or the **query string**
  - **OAuth 2.0** — client-credentials, and authorization-code **with PKCE**; tokens are cached and
    auto-refreshed
  - **AWS Signature v4**, **Digest**, **HMAC**, **NTLM** — all verified against published test vectors
  - **TLS / mTLS** — truststore, client keystore, or trust-all for a lab
- **Pre-request Script** — one statement per line: `set VAR = <expr>`, with `now()`, `isoNow()`,
  `uuid()`, `base64(x)`, `hmacSha256(key, msg)` and friends. Runs before Send.
- **Tests** — assertions on status, status range, header equals/contains, body contains, and JSON
  path equals. Results appear in the **Test Results** tab and travel with the request through
  history replay and the collection runner.
- **Extract** — rules that lift a value out of the response and name it, so the next request can use
  `${name}`:

  ```
  token = json_path: /data/token      # a dotted path like data.token works too
  rid   = header: X-Request-Id
  id    = regex: "id":\s*(\d+)
  code  = status
  ```

  Press **Test on the last response** to see what each rule produces. Extracted values are
  session-scoped: they are visible to every tab and are never written to your environment file,
  because a captured token belongs to this session only.
- **Settings** — connect / read timeouts, follow-redirects, cookie jar.

## Response panel

- **Body** — Pretty / Raw / **Hex**, with **Save to file…** (bytes as received, so a download or an
  image comes out intact).
- **Headers**, **Cookies** (the jar, replayed on later requests), **Timeline** (a waterfall of DNS,
  connect, TLS, TTFB and download), **Trace** (Zipkin/W3C spans), **Test Results**.

## Collections and the runner

**Collections** opens a request tree — folders, drag to reorder, import/export. Save the current
request into a folder, or update a saved one from the editor.

Right-click a folder ▸ **Run this folder…** runs every request under it in order:

- **Iterations** — run the list N times.
- **Data file** — a CSV (header row) or JSON array; each row runs the list once with its values
  available as `${column}`. The row count decides how many iterations run.
- **Delay** between requests, and **Stop on first failure**.
- A live table shows each request's status, duration and pass/fail, with the assertion that failed.
  Values extracted during the run feed the next request and stay available afterwards.

A step passes when the request completed **and** every assertion on it held.

## Handy actions

- **`</>`** — generate client code (cURL, Python, JavaScript, Java, PowerShell) with a copy button.
- **Import cURL** — paste a `curl` command and get a filled-in request.
- **HAR** — export the whole session as HAR 1.2.
- **Save** — store the request as a connection (secrets go to the encrypted vault).
- Every call is recorded in **History** (bottom panel) — ★ to favourite, **Replay** to reopen.

See also: **Security & Authentication**, **Code Generation**, **Environment Variables**.
