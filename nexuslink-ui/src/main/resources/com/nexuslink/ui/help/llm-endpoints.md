# LLM Endpoints (AI tester)

The AI tester sends a single-turn request to a model and shows the reply, the timing and the token usage. There are two ways to reach a model.

## Anthropic (SDK) — the default
Uses the official Anthropic Java SDK with `ANTHROPIC_API_KEY` from your environment. Pick a model from the dropdown and send. Nothing else to configure.

## A configured endpoint
Choose **Configure…** to describe any other endpoint — an internal AI gateway, a self-hosted model, or a vendor account fronted by your company's proxy. This is the path to use when the model is not at the vendor's public hostname.

### Endpoint tab
- **API format** — the request/response shape the endpoint speaks, *not* the vendor. Many internal gateways put an OpenAI-compatible API in front of other models, so pick what the endpoint accepts.
  - *Anthropic* → `POST /v1/messages`
  - *OpenAI* → `POST /v1/chat/completions`
- **Base URL** — e.g. `https://ai-gateway.corp.example.com`.
- **Path** — leave empty for the format's standard path. If your calls return **404**, the gateway is probably mounting the API somewhere else; set this explicitly.
- **Model** — the model id the endpoint expects. A configured endpoint carries its own model, so the model dropdown in the toolbar doesn't apply to it.

### Authentication tab
- **None** — no credential is sent; for gateways that authenticate by network position or client certificate.
- **API key** — sent in its own header (`x-api-key` for Anthropic, `api-key` for Azure-style endpoints; the name is editable).
- **Bearer token** — a token you already hold, sent as `Authorization: Bearer …`.
- **OIDC (client credentials)** — the usual choice for an internal service API. Give the token URL, client id, client secret and scope; NexusLink exchanges them for an access token, sends it as a bearer token, and refreshes it before it expires.

**About secrets:** type a value to use it for this session only, or write `${ENV_VAR}` to read it from your environment. Only the `${ENV_VAR}` form is saved to `~/.nexuslink/llm-endpoints.json` — a literal secret is never written to disk. Put long-lived credentials in an environment variable (or your own secret manager) and reference them.

### Parameters tab
Max tokens, temperature, top P, top K, stop sequences, extended-thinking budget, `anthropic-version`, and connect/read timeouts.

An empty field means the parameter is **not sent at all**, so the endpoint's own default applies. Nothing is invented on your behalf, because strict gateways reject unknown or null fields. Top K and the thinking budget apply to the Anthropic format only.

### Headers tab
Extra headers sent with every request, one per line as `Name: value` — for gateway routing, tenancy, or cost-centre attribution. These override NexusLink's own defaults if the names collide.

## Test connection
The **Test connection** button in the configure dialog sends a one-word prompt and reports what happened, so you find out here whether the URL, credential and model actually work.

## Networking
Requests honour the JVM proxy settings (`-Dhttps.proxyHost=… -Dhttps.proxyPort=…`, or `-Djava.net.useSystemProxies=true`). A gateway or proxy using a private certificate authority needs that CA in the JVM trust store: `-Djavax.net.ssl.trustStore=…`.

## Common errors
- **401 / 403** — the credential was rejected. For OIDC, confirm the token URL, client id and that the scope grants access to this endpoint.
- **404** — check the base URL and path; internal gateways often don't mount the API at the standard path.
- **429** — the endpoint is rate limiting you.
