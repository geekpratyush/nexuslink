# Security & Authentication

NexusLink supports secure, enterprise-grade connections — not just password-less access.

## Per-protocol auth
- **REST** — Basic, Bearer, API Key (header/query), OAuth 2.0 (client-credentials with token refresh).
- **SQL / Mongo / Redis** — username/password or connection-string credentials; TLS via the driver URL (`rediss://`, `mongodb+srv://`, JDBC SSL params).
- **Kafka** — security protocol `PLAINTEXT` / `SSL` / `SASL_PLAINTEXT` / `SASL_SSL`, SASL mechanism `PLAIN` / `SCRAM-SHA-256` / `SCRAM-SHA-512`.
- **gRPC** — plaintext or TLS channels.
- **SFTP** — password or SSH private-key auth.
- **S3 / object storage** — access key + secret (path-style for MinIO/Wasabi).

## Credential vault
Saved-connection secrets are encrypted with **AES-256-GCM** (PBKDF2, 200k iterations) — never stored as plaintext.

- On first save you set a **master password** (Tools ▸ Unlock Vault…). There is no recovery if you forget it.
- The vault **auto-locks** after 5 minutes of inactivity; the status bar shows 🔒 / 🔓 (click to toggle).
- When you **Save** a connection, its password/token/secret is moved into the vault and the profile keeps only a reference (`…Ref`). On open, the secret is resolved (unlocking the vault if needed).
