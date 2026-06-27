# TLS & Mutual TLS (certificates)

When you connect to an HTTPS server NexusLink verifies its certificate against a set of trusted CAs.
Two things can go wrong, and both are fixed by pointing the connection at the right certificate files:

1. **The server uses a private / self-signed certificate** your machine doesn't trust →
   give NexusLink a **trust store** containing that CA (or the server cert itself).
2. **The server requires a client certificate (mutual TLS / mTLS)** → give NexusLink a **key store**
   holding *your* client certificate + private key.

## Where to set it (REST client)

Open a REST tab → **Settings** tab → **TLS / mTLS** section:

- **Trust store** — *Browse…* to a `.p12`/`.pfx`/`.jks` file of the CA(s) to trust, and enter its
  password. Used to verify the server. Leave blank to use the system default CAs.
- **Client key store** — *Browse…* to a `.p12`/`.jks` holding your client certificate **and** private
  key, with its password. Only needed for mutual TLS.
- **Trust all certificates (insecure)** — skip verification entirely. Handy against a throwaway
  self-signed test server; never use it against anything real.

All paths support `${VAR}` substitution, so you can keep them in an environment.

## How to get the certificate files

Use the built-in **Certificate Manager** (Tools ▸ Certificate Manager…):

- **Import** a server's PEM/DER cert, then **Build Bundle… → CA trust store (PKCS#12)** to produce a
  trust store you can point the connection at.
- For a client cert, **Import Bundle…** your issued `.p12`, or **Generate CSR…**, have it signed, and
  bundle the cert + key with **Build Bundle… → PKCS#12 with private key**.
- Already have a chain? **Build Bundle… → Full-chain PEM** assembles leaf → intermediates → root.

> **Tip:** `keytool`/`openssl` files work too — a `.jks` truststore or a `.p12` client identity from
> your PKI drop straight into the Browse… fields.

## Common errors → fix

- `PKIX path building failed` / `unable to find valid certification path` → the server's CA isn't
  trusted: add it to the **trust store**.
- `bad_certificate` / handshake alert after the server asks for a cert → the server wants **mTLS**:
  set the **client key store**.
- `keystore password was incorrect` → wrong store password.

## Scope

TLS material is configurable for the **REST** client today; the same trust/key-store model is being
extended to the other TLS-based protocols. The `${VAR}`-resolved paths are saved with the request
(passwords are re-entered each session, like other secrets).
