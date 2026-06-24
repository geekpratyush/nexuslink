# Troubleshooting

Common errors and how to fix them.

## Connection refused
The host/port is wrong or the service isn't running. Check the address, that the server is up, and that no firewall blocks the port.

## SSL / TLS handshake failed
- The server's certificate isn't trusted, or you used `http`/`ws` where `https`/`wss` is required (or vice-versa).
- For self-signed certs, import the CA, or use a non-TLS endpoint for testing.

## Timeout
The server didn't respond in time. Increase the timeout (REST **Settings** tab), check network reachability, or the server load.

## 401 / 403 (auth failed)
The credentials or token are wrong/expired. Re-check the **Auth** tab. For OAuth 2.0, verify the token URL and client id/secret.

## Vault locked
Saved secrets are encrypted. Use **Tools ▸ Unlock Vault…** (or click 🔒 in the status bar) and enter your master password.

## A diagram shows code instead of a picture
ER / schema diagrams render with Mermaid, which loads from a CDN — they need **internet access** on first render. Plain Markdown help works offline.

## A public sample (HTTP 403 / blocked)
Some public APIs rate-limit or block by region. Try another sample, or your own endpoint.
