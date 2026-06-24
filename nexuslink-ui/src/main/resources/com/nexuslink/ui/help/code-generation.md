# Code Generation

Turn a REST request into ready-to-run client code.

## How to use
1. Build your request (method, URL, params, headers, body, auth).
2. Click **`</>`** on the REST bar.
3. Pick a language and **Copy** the snippet.

## Languages
- **cURL**
- **Python** (`requests`)
- **JavaScript** (`fetch`)
- **Java** (`java.net.http`)
- **PowerShell** (`Invoke-RestMethod`)

The generated snippet reflects the *effective* request — the URL with query params, headers, the resolved auth (Basic/Bearer/API-key), and the body — so what you copy matches what NexusLink sends. For OAuth 2.0, the snippet includes a placeholder `Authorization: Bearer <access_token>` header.
