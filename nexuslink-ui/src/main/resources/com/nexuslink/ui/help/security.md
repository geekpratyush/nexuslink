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

## Credentials are not shown in the clear

- **Connection strings** appear as a compact chip — the saved connection's name, or a short
  `host:port/database` label. A password inside the string (`mongodb://app:s3cret@…`,
  `jdbc:sqlserver://db;password=…`) is hidden while collapsed, so it is not on every screenshot or
  screen share. Double-click the chip to edit the real value. The right-click menu offers **Copy
  (credentials hidden)** as well as **Copy including credentials**, so revealing one is deliberate.
- **Secret fields** — bearer tokens, API keys, AWS session tokens, client secrets — are masked with
  an eye toggle rather than left as plain text, so a pasted value can still be checked without
  leaving it on screen for the session.
- **The activity log** redacts credentials in connect lines, because a log gets exported and pasted
  into tickets.
- **Values extracted from a response** (the REST client's Extract tab) live in memory for the
  session only. They are never written to your environment file.
- **The vault** holds saved passwords and tokens encrypted; **Tools ▸ Lock Vault** makes them
  unreadable until you unlock it again.
