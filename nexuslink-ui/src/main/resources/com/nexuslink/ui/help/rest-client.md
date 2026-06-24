# REST Client

Send HTTP/HTTPS requests over HTTP/2 and inspect responses.

## Sending a request
1. Pick a method (GET, POST, …) and type a URL in the bar.
2. Press **Send** (or `Ctrl+Enter`).
3. The response panel shows a colour-coded status, timing (total / TTFB / download), size, HTTP version, and the body (JSON is auto–pretty-printed).

## Request tabs
- **Params** — query parameters (auto URL-encoded; a new row is added as you type).
- **Headers** — request headers.
- **Body** — `NONE` / `JSON` / `XML` / `TEXT` / `FORM_URLENCODED`, with a *Format JSON* button.
- **Auth** — choose a type:
  - **Basic** — username / password
  - **Bearer** — a token
  - **API Key** — key name + value, sent in a **header** or the **query string**
  - **OAuth 2.0** — client-credentials grant (token URL, client id/secret, scope); the access token is fetched, cached, and auto-refreshed
- **Settings** — connect / read timeouts and follow-redirects.

## Handy actions
- **`</>`** — generate client code (cURL, Python, JavaScript, Java, PowerShell) with a copy button.
- **Save** — store the request as a connection (secrets go to the encrypted vault). It appears under *Saved* in the left panel.
- Every call is recorded in **History** (bottom panel) — click ★ to favourite or **Replay** to reopen it.

See also: **Security & Authentication**, **Code Generation**.
