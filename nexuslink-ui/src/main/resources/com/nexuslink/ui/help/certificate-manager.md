# Certificate Manager

A workspace for inspecting, generating, and managing X.509 certificates — open it from
**Tools ▸ Certificate Manager…**.

## The certificate list

Each certificate in the working store appears in the left list, colour-coded by validity:

- **Green** — valid
- **Amber** — expiring within 30 days, or not yet valid
- **Red** — expired

Select one to see its full X.509 details on the right: subject, issuer (with a *self-signed*
marker), serial, validity window, key algorithm + size, signature algorithm, the CA flag,
Subject Alternative Names, and the SHA-256 fingerprint.

## Generating a self-signed certificate

Click **Generate Self-Signed…** and set:

- **Common Name** (e.g. `localhost`) and **Organization**
- **Key type** — RSA 2048/4096 or EC P-256/P-384
- **Validity** in days
- **SANs** — comma-separated DNS names, or `IP:127.0.0.1` for an IP address

The new certificate and its private key are added to the working store. These are ideal for
local TLS/mTLS testing (`https://`, `wss://`, `rediss://`, gRPC TLS, Kafka `SSL`).

## Importing & exporting

- **Import…** reads a certificate from a `.pem`, `.crt`, `.cer`, or `.der` file and stores it
  as a trusted entry.
- **Export PEM…** writes the selected certificate to a PEM file.

## Saving the store

The working set lives in memory until you **Save Store…** to a password-protected PKCS12
keystore (key entries keep their private keys). Reopen it later with **Open Store…**. JKS
keystores can also be opened.

## Export, import & bundles

- **Export…** writes the selected certificate as **PEM**, **DER**, or a password-protected
  **PKCS#12** (the format is chosen by the file extension you pick).
- **Import…** loads a single PEM/DER certificate; **Import Bundle…** loads every entry of a
  **PKCS#12 / JKS** bundle (cert chains + private keys).
- **Generate CSR…** makes a key pair + PKCS#10 certificate-signing request to send to a CA.
- **Build Bundle…** is a guided builder: pick certificates, order them leaf → root, and produce a
  full-chain PEM, a PKCS#12 with the private key, or a CA trust bundle/store — with on-screen
  guidance about where each format is used.

## Using certificates to connect (TLS / mTLS)

To trust a private/self-signed server or present a client certificate for mutual TLS, build a
trust store / client key store here, then point the connection at it in the protocol tab's
**Settings ▸ TLS / mTLS** section. See **TLS & Mutual TLS (certificates)**.
