# Certificate Manager

> **Status:** on the roadmap (Phase 1.2).

A planned certificate workspace for TLS/mTLS testing:

- Import certificates (PEM / DER / PKCS12 / JKS) and inspect X.509 fields (subject, issuer, SAN, key usage, validity).
- Generate self-signed RSA/ECDSA certs with a configurable SAN and validity.
- Export to PEM / DER / PKCS12.
- An expiry watchdog that warns at 30 / 7 / 1 days.

Until then, TLS is configured per connection (e.g. `https://`, `wss://`, `rediss://`, `mongodb+srv://`, JDBC SSL params, Kafka `SSL`/`SASL_SSL`, gRPC TLS). See **Security & Authentication**.
