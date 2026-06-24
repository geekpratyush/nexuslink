# NexusLink — Universal Connectivity Workbench

> **Version:** 1.0.0  
> **Status:** Specification Document  
> **Framework:** RouteForge  
> **Platform:** JavaFX Desktop Application  
> **Java Version:** 17+ LTS  
> **JavaFX Version:** 21+

---

> ### ⚙️ Implementation Status
> This document is the **specification** (the north star). It is aspirational and not yet
> fully implemented. For what actually exists today, see:
> - **`README.md`** — feature status table + quick start
> - **`TASKS.md`** — the living, phase-by-phase build tracker and resume point
> - **`docs/ARCHITECTURE.md`** — module layout and patterns
>
> **Working today:** workspace shell, help system, credential vault, request history,
> REST client, WebSocket client, JDBC SQL client, MCP Inspector, and an AI Agent/LLM tester.
> Note: the runtime targets **Java 21** (virtual threads), a step up from the 17+ baseline below.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Product Vision & Naming](#2-product-vision--naming)
3. [Supported Protocols & Connectors](#3-supported-protocols--connectors)
4. [Security & Authentication Architecture](#4-security--authentication-architecture)
5. [Core Application Features](#5-core-application-features)
6. [UI/UX Design Philosophy](#6-uiux-design-philosophy)
7. [Technical Architecture](#7-technical-architecture)
8. [Implementation Roadmap](#8-implementation-roadmap)
9. [Visual Identity](#9-visual-identity)
10. [Prompt Engineering for AI Tools](#10-prompt-engineering-for-ai-tools)
11. [Appendix: Protocol Deep-Dives](#11-appendix-protocol-deep-dives)

---

## 1. Executive Summary

**NexusLink** is a professional-grade JavaFX desktop application designed as a universal connectivity workbench for enterprise integration developers, DevOps engineers, and system architects. It consolidates the functionality of disparate tools — Postman, Kafka Tool, FileZilla, MQTT Explorer, MQ Explorer, Solace PubSub+ Manager — into a single, cohesive, extensible platform.

The tool eliminates context switching by providing a unified interface for testing, debugging, and managing connections across every major protocol and transport layer used in modern distributed systems. Every connection supports enterprise-grade security configurations including mTLS, Kerberos, OAuth 2.0, certificate management, and secret vault integration.

### Key Differentiators
- **Protocol Agnostic:** One tool for REST, gRPC, Kafka, MQ, MQTT, SFTP, and 20+ other protocols
- **Security-First:** Built-in certificate manager, credential vault, mTLS, Kerberos, and secret vault integration
- **Developer Experience:** Code generation, request history, environment variables, and team collaboration
- **Extensible Architecture:** Plugin system for custom protocol handlers and enterprise extensions

---

## 2. Product Vision & Naming

### 2.1 Name: NexusLink

**Etymology:**
- **Nexus** — A connection or series of connections linking two or more things; a central or focal point
- **Link** — To make, form, or suggest a connection with or between

**Why NexusLink:**
- Abstract enough to not be tied to any single protocol
- Evokes connectivity, centrality, and bridging — exactly what the tool does
- Memorable, pronounceable, and available across domains
- Works well in enterprise contexts without sounding consumer-oriented

### 2.2 Taglines
- *"One Console. Every Protocol. Zero Context Switching."*
- *"The Universal Bridge for Distributed Systems"*
- *"Connect Anything. Test Everything."*

### 2.3 Brand Values
| Value | Description |
|-------|-------------|
| **Universal** | No protocol left behind — from legacy CORBA to modern gRPC |
| **Secure** | Enterprise-grade security is default, not an afterthought |
| **Intuitive** | Complex protocols made accessible through thoughtful UX |
| **Extensible** | Built to grow with your infrastructure |
| **Reliable** | Production-tested patterns, robust error handling, zero data loss |

---

## 3. Supported Protocols & Connectors

### 3.1 HTTP-Based Protocols

#### 3.1.1 REST API
| Feature | Specification |
|---------|-------------|
| HTTP Versions | HTTP/1.1, HTTP/2 (with ALPN) |
| Methods | GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE, CONNECT, CUSTOM |
| Request Body | JSON, XML, Form-Data (multipart), x-www-form-urlencoded, Raw (text/binary), GraphQL, File Upload |
| Response Handling | Pretty print (JSON/XML/HTML), Raw, Hex dump, Image preview, PDF preview |
| Headers | Custom headers, auto-complete from history, header presets, dynamic headers |
| Query Parameters | Key-value editor, URL encoding, array parameters, nested objects |
| Path Parameters | Template variables with auto-extraction (`/users/{id}`) |
| Cookies | Cookie jar with domain/path scoping, import/export, persistent storage |
| Redirects | Follow/none/manual, max redirects, preserve method on redirect |
| Timeouts | Connection timeout, read timeout, write timeout, per-request override |
| Compression | Gzip, Deflate, Brotli (auto-decompress) |
| Caching | HTTP cache simulation, ETag/If-None-Match, Last-Modified |
| Code Generation | cURL, Java (OkHttp/Apache HttpClient), Python (requests/httpx), Go, JavaScript (fetch/axios), PowerShell |

#### 3.1.2 gRPC
| Feature | Specification |
|---------|-------------|
| Streaming Modes | Unary, Server Streaming, Client Streaming, Bidirectional Streaming |
| Proto Loading | File picker for `.proto` files, directory scanning, no code generation required |
| Dynamic Messages | Runtime protobuf↔JSON conversion using `protobuf-java-util` |
| Reflection | gRPC Server Reflection for dynamic service discovery |
| Metadata | Custom metadata headers, trailing metadata display |
| Deadlines | Per-call timeout configuration, deadline propagation |
| TLS | Standard TLS and mTLS with client certificates |
| Code Generation | Java, Go, Python, C++, Node.js stub generation preview |
| Message History | Persist sent/received messages with replay capability |
| Import Resolution | Recursive `.proto` import resolution with path configuration |

#### 3.1.3 GraphQL
| Feature | Specification |
|---------|-------------|
| Operations | Query, Mutation, Subscription (WebSocket transport) |
| Introspection | Auto-fetch schema, type explorer, field documentation |
| Variables | JSON variable editor with validation against schema |
| Fragments | Named fragment support, fragment auto-completion |
| Response | Tree view, flat table view, error highlighting |
| Headers | Custom HTTP headers, Apollo-style persisted queries |

#### 3.1.4 WebSocket
| Feature | Specification |
|---------|-------------|
| Subprotocols | Custom subprotocol negotiation (RFC 6455) |
| Message Types | Text, Binary, Ping/Pong, Close frames |
| Auto-Reconnect | Configurable exponential backoff, max retry count |
| Message History | Persistent log with timestamp, direction, size |
| Compression | Per-message deflate extension |
| Heartbeat | Client-initiated ping interval, server pong timeout |

#### 3.1.5 Server-Sent Events (SSE)
| Feature | Specification |
|---------|-------------|
| Event Parsing | `id`, `event`, `data`, `retry` field extraction |
| Auto-Reconnect | `Last-Event-ID` header preservation |
| Event Filtering | Subscribe to specific event types |
| Stream Display | Real-time append with pause/resume |

---

### 3.2 Messaging & Event Streaming

#### 3.2.1 Apache Kafka
| Feature | Specification |
|---------|-------------|
| Producer | Sync/async send, batching, compression (Snappy/LZ4/Zstd/Gzip), idempotency, transactions |
| Consumer | Consumer groups, manual/auto offset commit, partition assignment strategies, rebalance listeners |
| AdminClient | Topic CRUD, partition management, config alteration, ACL management, consumer group management |
| Schema Registry | Confluent/Apicurio support, Avro/Protobuf/JSON Schema, compatibility modes (NONE/BACKWARD/FORWARD/FULL), schema evolution viewer |
| Kafka Connect | Connector deployment, task monitoring, config validation, offset tracking |
| ksqlDB | Stream/table browser, push/pull queries, query history |
| Security | SASL/PLAIN, SASL/SCRAM, SASL/GSSAPI (Kerberos), SASL/OAUTHBEARER, SSL/TLS, mTLS, ACL viewer/editor |
| Monitoring | Consumer lag, partition offsets, throughput metrics, broker metadata |
| Message Browser | Poll with filters, key/value deserialization (String/JSON/Avro/Protobuf/Hex), header display, hex dump |

#### 3.2.2 Solace PubSub+
| Feature | Specification |
|---------|-------------|
| APIs | JCSMP (native Java), JMS, REST, WebSocket, MQTT, AMQP 1.0 |
| Endpoints | Topics, Queues, Topic Endpoints, Durable/Non-durable subscriptions |
| Messaging | Guaranteed (persistent) messaging, Direct (non-persistent), Transactions, Request/Reply |
| Replay | Message replay from log cache |
| Security | Client certificates, OAuth 2.0, Kerberos, LDAP, internal/auth-db |
| Management | VPN management, client username profiles, ACL profiles, queue browser |

#### 3.2.3 IBM MQ
| Feature | Specification |
|---------|-------------|
| APIs | JMS 2.0, Jakarta Messaging 3.0, Native MQ bindings (com.ibm.mq.allclient) |
| Destinations | Queues, Topics, Temporary destinations |
| Channels | Server-connection, Client-connection, MQIPT tunneling |
| Security | TLS/SSL channel security, AMS (Advanced Message Security), OAUTH2, LDAP, PAM |
| Message Types | TextMessage, BytesMessage, ObjectMessage, MapMessage, StreamMessage |
| Headers | RFH2 header parsing/display, MQMD fields, custom properties |
| Browse | Queue browser (peek without consuming), message selectors |
| Transactions | Local transactions, XA distributed transactions |
| Admin | Queue depth, channel status, listener management |

#### 3.2.4 ActiveMQ / Artemis
| Feature | Specification |
|---------|-------------|
| Protocols | OpenWire, AMQP, STOMP, MQTT, JMS |
| Management | Broker JMX management, queue/topic browser, message scheduling |
| Features | Durable subscriptions, message groups, priority, expiration, DLQ inspection, redelivery policy |
| Security | JAAS authentication, SSL, certificate-based auth |

#### 3.2.5 RabbitMQ
| Feature | Specification |
|---------|-------------|
| Protocol | AMQP 0.9.1 |
| Management API | Exchange/Queue/Binding viewer and editor, vhost management, user management |
| Features | Publisher confirms, consumer acks (auto/manual), dead letter exchange, TTL, max length, priority queues |
| Plugins | Stream plugin, Shovel, Federation, MQTT plugin, STOMP plugin |
| Security | TLS, SASL mechanisms, OAuth 2.0, LDAP |

#### 3.2.6 Cloud Messaging
| Service | Features |
|---------|----------|
| **AWS SQS** | Standard & FIFO queues, dead-letter queues, message attributes, batch send/receive, visibility timeout, CloudWatch metrics |
| **AWS SNS** | Topics, subscriptions (HTTP/S, Lambda, SQS, email, SMS), message filtering, delivery policies |
| **Azure Service Bus** | Queues, Topics, Subscriptions, Sessions, Dead-letter, Scheduled messages, Auto-forwarding, Partitioning |
| **Google Pub/Sub** | Topics, Subscriptions (push/pull), Ordering keys, Dead letter topics, Schema validation, Message retention |
| **Apache Pulsar** | Producers/Consumers, Topics, Subscriptions (exclusive/shared/failover/key_shared), Schema registry, Functions, Geo-replication |

---

### 3.3 Traditional Messaging

#### 3.3.1 JMS (Generic)
| Feature | Specification |
|---------|-------------|
| Spec Versions | JMS 1.1, JMS 2.0, Jakarta Messaging 3.0 |
| Connection | Connection factory configuration, JNDI lookup support |
| Destinations | Queues, Topics, Temporary destinations |
| Messages | TextMessage, BytesMessage, ObjectMessage, MapMessage, StreamMessage |
| Features | Durable subscriptions, message selectors (SQL92), transacted sessions, acknowledge modes (AUTO/CLIENT/DUPS_OK) |
| Providers | Generic (any JMS provider), WebLogic, WebSphere, HornetQ, SonicMQ |

#### 3.3.2 MQTT
| Feature | Specification |
|---------|-------------|
| Versions | MQTT v3.1.1, MQTT v5.0 |
| Client | Eclipse Paho Java |
| QoS Levels | 0 (At most once), 1 (At least once), 2 (Exactly once) |
| Features | Will/Testament, Retained messages, Clean/Persistent sessions, Shared subscriptions (v5), Topic aliases (v5), User properties (v5), Request/Response (v5) |
| Security | Username/password, TLS/SSL, certificate-based auth |

#### 3.3.3 AMQP 1.0
| Feature | Specification |
|---------|-------------|
| Client | Apache Qpid JMS |
| Connection | AMQP connection URLs, failover URLs |
| Nodes | Queues, Topics, Temporary queues |
| Settlement | At-least-once, At-most-once, Exactly-once |
| SASL | ANONYMOUS, EXTERNAL, PLAIN, SCRAM, GSSAPI |

---

### 3.4 File Transfer & Remote Access

#### 3.4.1 SFTP (SSH File Transfer Protocol)
| Feature | Specification |
|---------|-------------|
| Authentication | Password, Public key (RSA/ECDSA/Ed25519), Keyboard-interactive, Two-factor |
| Host Keys | Known_hosts management, host key fingerprint verification, strict host key checking |
| Operations | Upload, Download, Resume, Directory sync, Remote file browser, Permissions (chmod), Symbolic links |
| Proxy | SOCKS, HTTP proxy, ProxyJump (bastion hosts) |
| Security | Cipher selection (AES-256-GCM, ChaCha20-Poly1305), MAC algorithms, key exchange algorithms |

#### 3.4.2 FTP / FTPS
| Feature | Specification |
|---------|-------------|
| Modes | Active, Passive |
| Transfers | ASCII, Binary, EBCDIC |
| Security | FTPS (FTP over SSL/TLS) — Implicit (port 990) and Explicit (AUTH TLS) |
| Resume | REST command for resume support |
| Features | Directory listing (MLSD/MLST), SITE commands, quota |

#### 3.4.3 SCP
| Feature | Specification |
|---------|-------------|
| Operations | Secure copy over SSH, recursive operations, bandwidth limiting, preserve attributes |
| Security | Same SSH security as SFTP |

#### 3.4.4 SMB / CIFS
| Feature | Specification |
|---------|-------------|
| Versions | SMB 1.0/2.0/2.1/3.0/3.1.1 |
| Authentication | NTLM, NTLMv2, Kerberos (SPNEGO) |
| Operations | Directory enumeration, file CRUD, ACL management |

#### 3.4.5 Object Storage
| Service | Features |
|---------|----------|
| **AWS S3** | Bucket browser, multipart upload, presigned URLs, versioning, lifecycle policies, server-side encryption (SSE-S3/SSE-KMS/SSE-C), ACLs, CORS |
| **Google Cloud Storage** | Bucket management, object upload/download, signed URLs, versioning, lifecycle, IAM |
| **Azure Blob Storage** | Container browser, block/page/append blobs, SAS tokens, tiering (Hot/Cool/Archive), soft delete |

---

### 3.5 Database & Cache Connectivity

#### 3.5.1 JDBC (Universal SQL Client)
| Feature | Specification |
|---------|-------------|
| Drivers | Auto-detection and configuration for major databases |
| Query | SQL editor with syntax highlighting, auto-completion, query builder |
| Results | Grid view, JSON export, CSV export, Excel export, chart generation |
| Schema | Schema browser (tables, views, procedures, indexes), ER diagram generation |
| Management | Connection pooling (HikariCP), query history, execution plan viewer |
| Databases | PostgreSQL, MySQL, Oracle, SQL Server, SQLite, H2, DB2, MariaDB, CockroachDB |

#### 3.5.2 Redis
| Feature | Specification |
|---------|-------------|
| Data Types | String, Hash, List, Set, Sorted Set, Bitmap, HyperLogLog, Geo, Stream |
| Operations | All Redis commands with argument validation, pipeline support, Lua scripting |
| Pub/Sub | Channel subscription, pattern matching |
| Cluster | Redis Cluster, Sentinel support, node discovery |
| Security | TLS, ACL (Redis 6+), password auth |

#### 3.5.3 MongoDB
| Feature | Specification |
|---------|-------------|
| Operations | CRUD, Aggregation Pipeline, Index management, GridFS |
| Features | Change streams, schema validation, TTL indexes |
| Security | SCRAM, x.509, LDAP, Kerberos, TLS |

---

### 3.6 Enterprise & Legacy Protocols

#### 3.6.1 LDAP / Active Directory
| Feature | Specification |
|---------|-------------|
| Operations | Search (with filters), Bind, Compare, Add, Modify, Delete, ModifyDN |
| Security | SSL/TLS, StartTLS, SASL (GSSAPI, EXTERNAL, DIGEST-MD5) |
| Features | Connection pooling, paged results, schema browsing, LDIF import/export |

#### 3.6.2 SNMP
| Feature | Specification |
|---------|-------------|
| Versions | v1, v2c, v3 |
| Operations | GET, GETNEXT, GETBULK, SET, WALK |
| v3 Security | USM (User-based Security Model) — noAuthNoPriv, authNoPriv, authPriv (MD5/SHA, DES/AES) |
| MIB | MIB browser, OID resolver, trap receiver |

#### 3.6.3 Telnet / SSH Terminal
| Feature | Specification |
|---------|-------------|
| Terminal | VT100/VT220/xterm emulation, color support, Unicode |
| SSH | Key-based auth, agent forwarding, port forwarding (local/remote/dynamic), session recording |
| Features | Multi-tab sessions, SFTP integration, command history |

#### 3.6.4 CORBA / IIOP
| Feature | Specification |
|---------|-------------|
| ORB | JacORB or similar Java ORB integration |
| Features | Naming service browser, IDL compiler integration, stub generation |

---

## 4. Security & Authentication Architecture

### 4.1 Transport Security

#### 4.1.1 TLS / SSL
| Feature | Implementation |
|---------|---------------|
| Versions | TLS 1.2, TLS 1.3 |
| Cipher Suites | Configurable cipher suite selection (ECDHE, AES-GCM, ChaCha20-Poly1305) |
| Extensions | SNI (Server Name Indication), ALPN (Application-Layer Protocol Negotiation), OCSP stapling |
| Session | Session resumption (tickets + IDs), session ticket rotation |
| Validation | Certificate chain validation, hostname verification (toggleable for dev), certificate pinning per connection |

#### 4.1.2 mTLS (Mutual TLS)
| Feature | Implementation |
|---------|---------------|
| Client Certs | PEM, PKCS#12 (.p12/.pfx), JKS (Java KeyStore) format support |
| Key Types | RSA (2048/4096), ECDSA (P-256/P-384/P-521), Ed25519 |
| Truststore | System truststore, custom CA bundles, self-signed cert acceptance (with warning) |
| Chain | Full certificate chain loading and validation |
| Pinning | SHA-256 public key pinning per connection profile |

#### 4.1.3 Certificate Manager
| Feature | Implementation |
|---------|---------------|
| Viewer | X.509 certificate parsing and display (subject, issuer, SAN, key usage, extensions, validity) |
| Import | Drag-and-drop import, PEM/DER/PKCS12/JKS format support |
| Export | Export to PEM/DER/PKCS12 with optional password protection |
| Generation | Self-signed certificate generation (RSA/ECDSA, configurable validity, SAN) |
| CA Bundles | CA certificate bundle management, custom trust anchors |
| Warnings | Expiration warnings (30/7/1 day), expired certificate blocking |
| Store | Encrypted local certificate store with master password |

#### 4.1.4 SSH Security
| Feature | Implementation |
|---------|---------------|
| Key Types | RSA, ECDSA, Ed25519 key generation |
| Passphrase | Passphrase-protected keys with secure prompt |
| Agent | SSH agent forwarding support, Pageant (Windows) integration |
| Known Hosts | Strict host key checking, known_hosts file management, fingerprint verification |

---

### 4.2 Authentication Mechanisms

#### 4.2.1 HTTP Authentication
| Mechanism | Details |
|-----------|---------|
| **Basic Auth** | Username/password with charset encoding (ISO-8859-1/UTF-8), preemptive/non-preemptive |
| **Bearer Token** | OAuth 2.0 access tokens, JWT (RS256/ES256/HS256/EdDSA), token introspection, custom token sources |
| **API Keys** | Header, query parameter, or cookie placement; multiple key support; key rotation |
| **Digest Auth** | RFC 7616 compliance, qop (quality of protection) support, nonce counting |
| **NTLM** | Windows integrated authentication, domain/workstation configuration, NTLMv2 |
| **AWS Signature V4** | Request signing with credential scope, STS assume role, session tokens |
| **HMAC** | Custom header signing (HMAC-SHA256/SHA512), timestamp inclusion, nonce |
| **Custom Auth** | Scriptable pre-request auth hooks (JavaScript/Groovy scripting engine) |

#### 4.2.2 OAuth 2.0 & OIDC
| Flow | Support |
|------|---------|
| **Authorization Code** | With PKCE (S256), state parameter, redirect URI handling |
| **Client Credentials** | Machine-to-machine, scope configuration |
| **Resource Owner Password** | Legacy support with deprecation warning |
| **Device Code** | TV/device flow with polling |
| **Implicit** | Legacy support with security warnings |
| **OIDC** | Discovery (.well-known/openid-configuration), ID token validation, userinfo endpoint, logout (RP-initiated) |
| **Token Management** | Automatic refresh, token storage, token revocation |

#### 4.2.3 SAML
| Feature | Implementation |
|---------|---------------|
| Flow | SP-initiated SSO, IdP-initiated SSO |
| Parsing | SAML assertion parsing and display |
| Validation | Certificate validation, signature verification, clock skew tolerance |
| Binding | HTTP-Redirect, HTTP-POST, Artifact |

#### 4.2.4 Kerberos / SPNEGO
| Feature | Implementation |
|---------|---------------|
| JAAS | JAAS configuration file support, krb5.conf integration |
| Keytabs | Keytab file loading for service accounts |
| SPN | Service Principal Name configuration |
| Delegation | Credential delegation (constrained/unconstrained) |
| Cross-Realm | Cross-realm trust configuration |
| Fallback | NTLM fallback when Kerberos unavailable |

#### 4.2.5 SASL Mechanisms (Messaging)
| Mechanism | Protocols |
|-----------|-----------|
| **PLAIN** | Kafka, LDAP, SMTP |
| **SCRAM-SHA-256 / SCRAM-SHA-512** | Kafka, XMPP |
| **GSSAPI (Kerberos)** | Kafka, LDAP, JMS |
| **EXTERNAL (TLS client cert)** | Kafka, AMQP, LDAP |
| **OAUTHBEARER** | Kafka |
| **AWS_MSK_IAM** | AWS MSK Kafka |
| **DIGEST-MD5** | LDAP, SMTP |
| **CRAM-MD5** | SMTP, IMAP |

---

### 4.3 Authorization & Access Control

| Feature | Implementation |
|---------|---------------|
| **Kafka ACLs** | Viewer and editor for topic/consumer-group/cluster ACLs, principal management |
| **Solace Client Profiles** | Client username profiles, ACL profiles, queue/topic endpoint permissions |
| **MQ Authority** | Object authority manager (OAM), queue manager authority records |
| **RBAC** | Role-based connection profiles (admin, developer, read-only), team sharing with permission levels |

---

### 4.4 Secret Management

#### 4.4.1 Local Credential Vault
| Feature | Implementation |
|---------|---------------|
| Encryption | AES-256-GCM with PBKDF2 key derivation (100,000+ iterations) |
| Master Password | Single master password with strength meter, optional biometric unlock (OS-dependent) |
| Auto-Lock | Configurable auto-lock timeout (1 min / 5 min / 15 min / never) |
| Backup | Encrypted backup/restore to file |

#### 4.4.2 External Secret Vaults
| Vault | Integration |
|-------|-------------|
| **HashiCorp Vault** | KV v1/v2, AppRole auth, AWS/Azure/GCP IAM auth, Kubernetes auth, dynamic secrets |
| **AWS Secrets Manager** | IAM role-based access, secret rotation, cross-account access |
| **Azure Key Vault** | Managed identity, service principal, certificate-based auth |
| **CyberArk Conjur** | Machine identity, secret retrieval |
| **1Password** | Connect server integration (read-only) |

#### 4.4.3 Environment Configuration
| Feature | Implementation |
|---------|---------------|
| **Environment Variables** | System environment variable substitution in all connection fields (`${ENV_VAR}`) |
| **.env Files** | Project-specific `.env` file loading with variable substitution |
| **Profile Variables** | Per-connection-profile variable sets (dev/staging/prod) |
| **Secret Masking** | Password/token masking in UI with reveal toggle, clipboard copy |

---

## 5. Core Application Features

### 5.1 Connection Management

#### 5.1.1 Connection Profiles
| Feature | Implementation |
|---------|---------------|
| Organization | Folders, tags, color coding, favorites, search |
| Templates | Pre-built templates for common services (AWS, Azure, Confluent Cloud, Solace Cloud) |
| Import/Export | Encrypted JSON export, team sharing via secure link |
| Sync | Optional cloud sync (encrypted at rest) |
| Validation | Pre-save validation with detailed error messages |

#### 5.1.2 Connection Pooling
| Feature | Implementation |
|---------|---------------|
| Pooling | Reusable connection pools with configurable max/min size |
| Health | Idle timeout, connection validation queries, health checks |
| Monitoring | Pool metrics (active/idle/waiting), leak detection |

#### 5.1.3 Connection Testing
| Feature | Implementation |
|---------|---------------|
| Test Button | One-click "Test Connection" with detailed diagnostics |
| Diagnostics | DNS resolution, TCP connect, TLS handshake, auth negotiation, protocol handshake — each with timing |
| Troubleshooting | Suggested fixes for common errors (cert issues, auth failures, network timeouts) |

#### 5.1.4 Failover & Resilience
| Feature | Implementation |
|---------|---------------|
| Failover | Multi-node failover with priority ordering |
| Retry | Exponential backoff retry with jitter, max retry count, circuit breaker pattern |
| Health Checks | Configurable heartbeat/ping for persistent connections |

---

### 5.2 Request / Message Construction

#### 5.2.1 Visual Builder
| Feature | Implementation |
|---------|---------------|
| Forms | Form-based UI for constructing requests without writing code |
| Validation | Real-time validation against protocol specifications |
| Templates | Save and reuse request templates |

#### 5.2.2 Raw Editors
| Feature | Implementation |
|---------|---------------|
| Syntax Highlighting | JSON, XML, YAML, Protobuf, SQL, JavaScript, Groovy |
| Auto-Completion | Context-aware auto-completion based on schema/proto |
| Formatting | Pretty print, minify, canonicalize |
| Diff | Side-by-side diff view |

#### 5.2.3 Dynamic Content
| Feature | Implementation |
|---------|---------------|
| Variables | `${variable}` substitution with environment/profile scope |
| Expressions | Dynamic expressions (timestamp, UUID, random, base64, hash) |
| Pre-Request Scripts | JavaScript/Groovy hooks for dynamic request modification |
| File Attachments | Drag-and-drop binary/multipart upload, file preview |

#### 5.2.4 Schema Validation
| Feature | Implementation |
|---------|---------------|
| JSON Schema | Draft 7/2019-09/2020-12 validation |
| XML Schema | XSD validation |
| Protobuf | Runtime message validation against descriptor |
| Avro | Schema validation with Confluent registry |

#### 5.2.5 Code Generation
| Language | Output |
|----------|--------|
| Java | OkHttp, Apache HttpClient, Kafka Client, JMS |
| Python | requests, httpx, kafka-python, paho-mqtt |
| Go | net/http, grpc-go, sarama |
| JavaScript | fetch, axios, grpc-web |
| cURL | Direct cURL command |
| PowerShell | Invoke-RestMethod, Invoke-WebRequest |

---

### 5.3 Response / Message Handling

#### 5.3.1 Response Viewers
| Format | Viewer |
|--------|--------|
| JSON | Tree view, collapsible, search, path copy, JSONPath query |
| XML | Tree view with namespace handling, XPath query |
| HTML | Rendered preview with stylesheet support |
| Image | Image preview (PNG/JPG/GIF/SVG/WebP) with zoom |
| Binary | Hex dump, base64, file download |
| Protobuf | Decoded message tree with field names |
| Avro | Decoded with schema reference |

#### 5.3.2 Response Tools
| Feature | Implementation |
|---------|---------------|
| Comparison | Diff view between multiple responses (textual/structural) |
| Export | Save to file, copy to clipboard, export as CSV/Excel/JSON/XML |
| Search | Full-text search across response body, headers, cookies |
| History | Persistent message history with search, favorites, replay |

#### 5.3.3 Streaming Display
| Feature | Implementation |
|---------|---------------|
| Real-Time | Live append display with pause/resume/clear |
| Rate Limiting | Throttled display for high-throughput streams |
| Filtering | Real-time message filtering by content, headers, or metadata |
| Statistics | Live throughput, latency, message count |

---

### 5.4 Kafka-Specific Features

| Feature | Description |
|---------|-------------|
| **Topic Management** | Create, delete, describe, alter configs, partition reassignment, replica placement |
| **Consumer Groups** | Lag monitoring per partition, offset reset (earliest/latest/specific), member details, partition assignment |
| **Schema Registry** | Subject management, compatibility mode configuration, schema evolution timeline, version comparison |
| **Kafka Connect** | Connector deployment (source/sink), task monitoring, config validation, offset tracking, dead letter queue |
| **ksqlDB** | Stream/table browser, persistent query listing, push query execution with live results, query termination |
| **Message Browser** | Poll with filters (key, value, header, partition, offset range), deserialization (String/JSON/Avro/Protobuf/Hex), hex dump, export |
| **Metrics** | Broker metrics, topic metrics, consumer lag graphs, throughput charts |

---

### 5.5 Messaging-Specific Features

| Feature | Description |
|---------|-------------|
| **Queue Browser** | Peek messages without consuming (JMS, MQ, Solace) with message selectors |
| **Message Properties** | Full header/property/attribute editor with type validation |
| **Priority & Expiry** | JMS priority (0-9), TTL, delay delivery, expiration handling |
| **Transaction Support** | Local transactions (commit/rollback), XA transaction coordination (2PC) |
| **Message Conversion** | Auto-convert between formats (JSON↔XML↔Protobuf↔Avro) with schema mapping |
| **Dead Letter** | DLQ inspection, redelivery count, poison message handling |

---

### 5.6 File Transfer Features

| Feature | Description |
|---------|-------------|
| **Dual-Pane Browser** | Local and remote file trees with drag-and-drop transfer |
| **Transfer Queue** | Batch operations with pause/resume/retry/cancel, bandwidth limiting |
| **Synchronization** | Bidirectional sync with conflict resolution (timestamp/size/hash comparison) |
| **Preview** | Text/image preview before download, syntax highlighting for code files |
| **Permissions** | Remote chmod, ACL management, ownership display |
| **Search** | Remote file search by name, size, date, content (where supported) |

---

### 5.7 Monitoring & Observability

#### 5.7.1 Connection Logs
| Feature | Implementation |
|---------|---------------|
| Levels | DEBUG, INFO, WARN, ERROR, TRACE |
| Filtering | Protocol filter, connection filter, text search, regex search |
| Export | Save to file, copy selection, clear |
| Protocol Details | Raw protocol frames for debugging (hex dump for binary protocols) |

#### 5.7.2 Metrics Dashboard
| Metric | Visualization |
|--------|---------------|
| Throughput | Messages/second, bytes/second (real-time line chart) |
| Latency | P50/P95/P99 histogram, heatmap over time |
| Error Rate | Error percentage with breakdown by type |
| Connection State | Active/idle/failed connection counts |
| Resource Usage | Memory, CPU, network I/O |

#### 5.7.3 Request Timeline
| Feature | Implementation |
|---------|---------------|
| Waterfall | Visual timeline of request phases: DNS lookup, TCP connect, TLS handshake, send, wait (TTFB), receive |
| Timing | Precise timing for each phase with total duration |
| Comparison | Side-by-side comparison of multiple requests |

#### 5.7.4 Distributed Tracing
| Feature | Implementation |
|---------|---------------|
| W3C Trace Context | Traceparent/tracestate header injection and parsing |
| Jaeger | Span creation, baggage, trace search |
| Zipkin | B3 header propagation, span export |
| Visualization | Trace tree view, span details, latency breakdown |

---

### 5.8 UI/UX Design Philosophy

#### 5.8.1 Workspace Layout
| Feature | Implementation |
|---------|---------------|
| Detachable Tabs | Drag tabs to create new windows, snap to edges |
| Panel Layout | Save/restore panel layouts, preset layouts (REST-focused, Messaging-focused, File-focused) |
| Sidebar | Collapsible left sidebar for connection tree, right sidebar for properties/details |
| Status Bar | Connection status, last operation, memory usage, version info |

#### 5.8.2 Theming
| Feature | Implementation |
|---------|---------------|
| Dark Mode | Full dark theme with high-contrast syntax highlighting |
| Light Mode | Clean light theme for bright environments |
| System Detection | Auto-switch based on OS preference |
| Custom Themes | CSS-based theme customization, community theme sharing |

#### 5.8.3 Productivity
| Feature | Implementation |
|---------|---------------|
| Keyboard Shortcuts | Vim/Emacs-style bindings, fully customizable shortcuts, shortcut cheat sheet |
| Global Search | `Ctrl+Shift+F` to search across connections, history, responses, topics, schemas |
| Quick Actions | `Ctrl+K` command palette for quick navigation |
| Notifications | Toast notifications for async events, connection status changes, errors |
| Auto-Save | Auto-save of in-progress requests, crash recovery |

#### 5.8.4 Accessibility
| Feature | Implementation |
|---------|---------------|
| WCAG 2.1 AA | Color contrast, keyboard navigation, screen reader support |
| Font Scaling | Independent UI font scaling |
| High Contrast | High contrast mode for visually impaired users |

---

## 6. UI/UX Design Philosophy

### 6.1 Design Principles

1. **Progressive Disclosure** — Show only what is needed. Advanced options are one click away, never hidden in deep menus.
2. **Protocol Consistency** — Common patterns (connect → construct → send → inspect) apply across all protocols.
3. **Context Preservation** — Switching between protocols retains workspace state. No data loss on tab switch.
4. **Error Empathy** — Errors are explained, not just reported. Suggest fixes, link to documentation.
5. **Performance Transparency** — Every operation shows timing, throughput, and resource impact.

### 6.2 Visual Hierarchy

```
PRIMARY ACTIONS (Blue/Accent)
├── Connect / Send / Execute
├── Save / Export
└── Test Connection

SECONDARY ACTIONS (Gray)
├── Add Header / Parameter
├── Clear / Reset
└── Copy / Paste

DESTRUCTIVE ACTIONS (Red)
├── Disconnect / Stop
├── Delete
└── Clear History

INFORMATIONAL (Green/Yellow)
├── Success indicators
├── Warning states
└── Status badges
```

### 6.3 Screen Layout Reference

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ [File] [Edit] [View] [Connection] [Tools] [Window] [Help]    🔍 Search...   │
├──────────┬──────────────────────────────────────────────────────────────────┤
│          │  [REST API ▼] [Kafka ▼] [SFTP ▼] [+]  —  Detachable Tabs       │
│  ◄       │  ┌─────────────────────────────────────────────────────────────┐ │
│ CONNECT- │  │ [GET ▼] [https://api.example.com/v1/users........] [Send]   │ │
│ IONS     │  ├─────────────────────────────────────────────────────────────┤ │
│ TREE     │  │ Params │ Auth │ Headers │ Body │ Pre-Req │ Settings         │ │
│          │  ├─────────────────────────────────────────────────────────────┤ │
│ 📁 Prod  │  │ Key              │ Value            │ Description          │ │
│  ├── 🔗  │  │ page             │ 1                │ Pagination           │ │
│  ├── 📨  │  │ limit            │ 10               │ Page size            │ │
│  └── 🔒  │  │ [+ Add Parameter]                                       │ │
│ 📁 Stage │  │                                                          │ │
│  ├── 🔗  │  ├─────────────────────────────────────────────────────────────┤ │
│  └── 📨  │  │ Status: 200 OK │ 245ms │ 1.2KB                              │ │
│ 📁 Dev   │  │ Body │ Headers │ Cookies │ Test Results │ Timeline          │ │
│  └── 🔗  │  ├─────────────────────────────────────────────────────────────┤ │
│          │  │ {                                                           │ │
│          │  │   "data": [                                                 │ │
│          │  │     { "id": 1, "name": "Alice" },                          │ │
│          │  │     { "id": 2, "name": "Bob" }                               │ │
│          │  │   ],                                                        │ │
│          │  │   "meta": { "total": 100, "page": 1 }                       │ │
│          │  │ }                                                           │ │
│          │  └─────────────────────────────────────────────────────────────┘ │
├──────────┤                                                                   │
│ PROPERT- │  [Log Panel] — Collapsible bottom panel                        │
│ IES      │  14:32:01.245  DEBUG  HTTP  →  GET /v1/users 200 OK (245ms)   │
│ PANEL    │  14:32:01.001  DEBUG  DNS  →  api.example.com resolved         │
└──────────┴───────────────────────────────────────────────────────────────────┘
```

---

## 7. Technical Architecture

### 7.1 Technology Stack

| Layer | Technology |
|-------|------------|
| **Language** | Java 17+ LTS |
| **UI Framework** | JavaFX 21+, FXML, CSS 3 |
| **Build Tool** | Maven 3.9+ (or Gradle 8+) |
| **Packaging** | jlink + jpackage (native installers) |
| **Modularity** | JPMS (Java Platform Module System) |

### 7.2 Key Dependencies

| Purpose | Library | Version |
|---------|---------|---------|
| **HTTP/REST** | OkHttp | 4.x |
| | Apache HttpClient 5 | 5.x |
| **gRPC** | grpc-java | 1.6x |
| | protobuf-java | 3.x |
| **Kafka** | kafka-clients | 3.7+ |
| | kafka-admin-client | 3.7+ |
| **JMS** | jakarta.jms-api | 3.1 |
| **MQTT** | Eclipse Paho Java | 1.2+ |
| **AMQP** | Apache Qpid JMS | 1.x |
| **SFTP/SSH** | Apache MINA SSHD | 2.12+ |
| | JSch (legacy) | 0.2.x |
| **IBM MQ** | com.ibm.mq.allclient | 9.3+ |
| **Solace** | sol-jcsmp | 10.x |
| | sol-jms | 10.x |
| **Security** | BouncyCastle | 1.78+ |
| | Java Security (JCA/JCE) | Built-in |
| **JSON** | Jackson | 2.17+ |
| | JSON-P (Jakarta) | 2.1+ |
| **XML** | JAXB (Jakarta) | 4.0+ |
| | Woodstox | 6.x |
| **Database** | JDBC | 4.3+ |
| | HikariCP | 5.x |
| **UI Enhancements** | RichTextFX | 0.11+ |
| | ControlsFX | 11.x |
| | FontAwesomeFX | 4.x |
| | JFoenix (Material) | 9.x |
| **Reactive** | RxJava | 3.x |
| | Project Reactor | 3.6+ |
| **Testing** | JUnit 5 | 5.10+ |
| | TestFX | 4.x |
| | Mockito | 5.x |

### 7.3 Architecture Patterns

```
┌─────────────────────────────────────────────────────────────────┐
│                         PRESENTATION LAYER                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Views     │  │  ViewModels │  │    Controllers          │  │
│  │  (FXML/CSS) │  │  (Reactive) │  │   (Event Handling)      │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                         SERVICE LAYER                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  Protocol   │  │   Security  │  │    Connection           │  │
│  │  Services   │  │   Service   │  │    Manager              │  │
│  │ (REST/Kafka)│  │(Auth/Certs) │  │  (Pool/Health/Failover) │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                         DATA LAYER                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Local     │  │   History   │  │    Secret               │  │
│  │   Config    │  │   Store     │  │    Vault                │  │
│  │ (JSON/YAML) │  │  (SQLite)   │  │  (Encrypted/External)   │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                      PLUGIN ARCHITECTURE                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   SPI       │  │   Plugin    │  │    Extension            │  │
│  │  Interface  │  │   Loader    │  │    Registry             │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 7.4 Module Structure (JPMS)

```
nexuslink.core          — Core framework, event bus, DI
nexuslink.ui            — JavaFX UI components, theming
nexuslink.security      — Certificate manager, auth, vault
nexuslink.protocol.http — REST, gRPC, GraphQL, WebSocket, SSE
nexuslink.protocol.messaging — Kafka, JMS, MQTT, AMQP, MQ, Solace
nexuslink.protocol.file — SFTP, FTP, SCP, SMB, S3, GCS, Azure Blob
nexuslink.protocol.db   — JDBC, Redis, MongoDB
nexuslink.protocol.enterprise — LDAP, SNMP, Telnet/SSH, CORBA
nexuslink.plugin.api    — Plugin SPI and extension points
```

### 7.5 Data Storage

| Data | Storage | Format |
|------|---------|--------|
| Connection Profiles | Local file system | Encrypted JSON |
| Request History | Embedded database | SQLite with FTS5 |
| Certificates | Local keystore | JKS/PKCS12 with AES-256 |
| Credentials | Local vault | AES-256-GCM encrypted |
| Settings | OS preferences | Java Preferences API |
| Cache | In-memory | Caffeine |
| Logs | Rolling files | Structured JSON |

---

## 8. Implementation Roadmap

### Phase 1: Foundation (Weeks 1–3)
- [ ] JavaFX skeleton with workspace layout, detachable tabs, dark theme
- [ ] Connection profile manager with folders, tags, import/export
- [ ] Certificate manager (import, export, generation, viewer, expiration warnings)
- [ ] Credential vault with AES-256 encryption and master password
- [ ] Environment variable system and `.env` file support
- [ ] Plugin architecture SPI definition

### Phase 2: HTTP Core (Weeks 4–6)
- [ ] REST client with all HTTP methods, headers, body editors
- [ ] Response viewers (JSON tree, XML tree, HTML preview, image, hex)
- [ ] Authentication: Basic, Bearer, API Key, OAuth 2.0 (all flows), Digest, NTLM
- [ ] TLS/mTLS with certificate selection
- [ ] Request history, collections, code generation
- [ ] WebSocket client with all message types and auto-reconnect

### Phase 3: Messaging Core (Weeks 7–10)
- [ ] Kafka client: Producer, Consumer, AdminClient, Schema Registry
- [ ] JMS generic client with connection factory wizard
- [ ] MQTT client (Paho, v3.1.1 & v5.0)
- [ ] AMQP 1.0 client (Qpid JMS)
- [ ] Message browser and queue peek functionality
- [ ] Consumer group monitoring and lag visualization

### Phase 4: Enterprise Messaging (Weeks 11–14)
- [ ] IBM MQ native integration (JMS + native bindings)
- [ ] Solace PubSub+ integration (JCSMP + JMS + REST)
- [ ] ActiveMQ/Artemis support
- [ ] RabbitMQ management integration
- [ ] Cloud messaging: AWS SQS/SNS, Azure Service Bus, Google Pub/Sub

### Phase 5: Advanced Protocols (Weeks 15–18)
- [ ] gRPC client with proto loading and all streaming modes
- [ ] GraphQL client with introspection and subscription support
- [ ] SSE client with event filtering
- [ ] Code generation for all protocols

### Phase 6: File Transfer (Weeks 19–22)
- [ ] SFTP/SCP with dual-pane browser and certificate auth
- [ ] FTP/FTPS with active/passive modes
- [ ] S3/Azure Blob/GCS browser with multipart upload
- [ ] File sync with conflict resolution

### Phase 7: Database & Enterprise (Weeks 23–26)
- [ ] JDBC universal SQL client with query builder
- [ ] Redis client with all data types
- [ ] MongoDB CRUD and aggregation
- [ ] LDAP/AD browser and search
- [ ] SNMP browser and trap receiver
- [ ] SSH terminal emulator

### Phase 8: Polish & Distribution (Weeks 27–30)
- [ ] Metrics dashboard with real-time charts
- [ ] Distributed tracing integration
- [ ] Team collaboration features
- [ ] External vault integration (HashiCorp Vault, AWS Secrets Manager)
- [ ] Performance optimization and memory profiling
- [ ] Native packaging (MSI, DMG, PKG, Deb, RPM)
- [ ] Auto-updater implementation
- [ ] Documentation and user guide

---

## 9. Visual Identity

### 9.1 Logo Concept

The NexusLink logo represents **interconnected nodes forming a gateway** — symbolizing the tool's ability to bridge any protocol to any endpoint.

**Visual Elements:**
- **Central Node** — A solid circle representing the unified console
- **Orbiting Nodes** — Smaller circles connected by lines, representing diverse protocols
- **Gateway Arc** — A subtle arc suggesting an open door or bridge
- **Color Gradient** — Deep indigo to electric cyan, suggesting depth and connectivity

**Symbolism:**
- The central node = NexusLink (the unified hub)
- The orbiting nodes = REST, Kafka, MQ, MQTT, SFTP, etc. (the protocols)
- The connecting lines = The seamless bridges between them
- The arc = The open gateway to enterprise systems

### 9.2 Color Palette

| Role | Color | Hex | Usage |
|------|-------|-----|-------|
| **Primary** | Deep Indigo | `#1E1B4B` | Headers, primary buttons, logo background |
| **Accent** | Electric Cyan | `#06B6D4` | Active states, links, highlights, progress |
| **Secondary** | Violet | `#8B5CF6` | Secondary actions, tags, badges |
| **Success** | Emerald | `#10B981` | Success states, connected status, valid certs |
| **Warning** | Amber | `#F59E0B` | Warnings, expiring certs, retries |
| **Danger** | Rose | `#F43F5E` | Errors, disconnect, delete, invalid certs |
| **Info** | Sky | `#0EA5E9` | Informational tooltips, hints |
| **Dark BG** | Slate 950 | `#020617` | Dark theme background |
| **Dark Surface** | Slate 900 | `#0F172A` | Dark theme panels, cards |
| **Dark Border** | Slate 800 | `#1E293B` | Dark theme borders, dividers |
| **Light BG** | Slate 50 | `#F8FAFC` | Light theme background |
| **Light Surface** | White | `#FFFFFF` | Light theme panels, cards |
| **Light Border** | Slate 200 | `#E2E8F0` | Light theme borders, dividers |
| **Text Primary** | Slate 100 | `#F1F5F9` | Dark theme primary text |
| **Text Secondary** | Slate 400 | `#94A3B8` | Dark theme secondary text |

### 9.3 Typography

| Role | Font | Weight | Size |
|------|------|--------|------|
| **Logo** | Inter | Bold (700) | 24px |
| **Headers** | Inter | SemiBold (600) | 18–32px |
| **Body** | Inter | Regular (400) | 13–14px |
| **Monospace** | JetBrains Mono | Regular (400) | 13px |
| **Small/Caption** | Inter | Medium (500) | 11–12px |

### 9.4 Iconography

| Icon | Meaning | Unicode/FontAwesome |
|------|---------|---------------------|
| 🔗 | REST/HTTP | `fa-link` |
| 📨 | Messaging | `fa-envelope` |
| 📁 | File Transfer | `fa-folder` |
| 🔒 | Security | `fa-lock` |
| ⚡ | Active/Connected | `fa-bolt` |
| 🔴 | Disconnected/Error | `fa-circle` (red) |
| 🟢 | Connected/Success | `fa-circle` (green) |
| 🟡 | Warning/Retry | `fa-circle` (yellow) |
| 🔍 | Search | `fa-search` |
| ⚙️ | Settings | `fa-cog` |
| 📊 | Metrics | `fa-chart-line` |
| 📋 | History | `fa-history` |
| 🔧 | Tools | `fa-wrench` |
| 🌐 | WebSocket | `fa-globe` |
| 📡 | gRPC | `fa-satellite-dish` |
| 🗄️ | Database | `fa-database` |

---

## 10. Prompt Engineering for AI Tools

### 10.1 Master Prompt Template

```
You are implementing a module for NexusLink, a universal connectivity 
workbench built with JavaFX. Follow these rules strictly:

ARCHITECTURE:
- Java 17+, JavaFX 21, Maven, JPMS modular architecture
- MVVM pattern: FXML (View) → ViewModel → Service layer
- Background tasks via JavaFX Task/Service — NEVER block UI thread
- Event-driven communication via internal event bus

SECURITY:
- NEVER store plaintext passwords — use CredentialVault with AES-256-GCM
- Certificate operations use BouncyCastle or Java Security APIs
- Validate all user inputs before network operations
- Resource cleanup in try-with-resources or finally blocks

CODE QUALITY:
- Comprehensive JavaDoc on all public APIs
- Null safety with Optional and Objects.requireNonNull
- Immutable data objects where possible
- Defensive copying for collections
- SLF4J logging at appropriate levels

TESTING:
- JUnit 5 for unit tests
- TestFX for UI tests
- Mockito for mocking
- Minimum 80% code coverage for service layer

CURRENT TASK: [Describe specific module/feature here]
```

### 10.2 Protocol-Specific Prompts

#### REST Client Module
```
Implement the REST client module for NexusLink with:
1. RequestBuilder service supporting all HTTP methods
2. OkHttp-based execution with configurable timeouts, redirects, compression
3. Response handling with pluggable viewers (JSON tree, XML tree, HTML, image, hex)
4. Authentication support: Basic, Bearer, OAuth2 (all flows), Digest, NTLM, mTLS
5. Request history with SQLite persistence and full-text search
6. Collection management with folder hierarchy and import/export
7. Code generation for Java/OkHttp, Python/requests, Go, JavaScript/fetch, cURL
8. Environment variable substitution (${VAR}) and .env file support
9. Pre-request script hooks (JavaScript engine via Nashorn/GraalJS)

UI Requirements:
- Method dropdown (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS/CUSTOM)
- URL bar with history dropdown and environment highlighting
- Tabbed panel: Params/Auth/Headers/Body/Pre-Req/Settings
- Response panel with status, timing, size, and viewer tabs
- Bottom log panel showing request/response details
```

#### Kafka Client Module
```
Implement the Kafka client module for NexusLink with:
1. Connection profile supporting: bootstrap servers, SASL mechanisms, SSL/TLS, mTLS
2. Producer panel: topic selection, key/value editors, header editor, batching config
3. Consumer panel: group ID, auto offset reset, partition assignment, message display
4. AdminClient panel: topic CRUD, config alteration, consumer group management
5. Schema Registry integration: Confluent and Apicurio, Avro/Protobuf/JSON Schema
6. Message browser: poll with filters, deserialization (String/JSON/Avro/Protobuf/Hex)
7. Consumer lag monitoring with real-time charts
8. ksqlDB query execution with live result streaming

UI Requirements:
- Connection wizard with test button and detailed diagnostics
- Topic tree with metadata (partitions, replicas, configs)
- Message table with sortable columns (offset, timestamp, key, value, headers)
- Schema viewer with version history and compatibility mode display
- Metrics dashboard with throughput and lag charts
```

#### Certificate Manager Module
```
Implement the Certificate Manager module for NexusLink with:
1. Certificate store: encrypted local storage with master password
2. Import: drag-and-drop PEM/DER/PKCS12/JKS files with password prompt
3. Viewer: X.509 field parsing (subject, issuer, SAN, key usage, extensions, validity period)
4. Generator: self-signed cert creation (RSA/ECDSA, key size, validity days, SAN list)
5. Export: PEM/DER/PKCS12 export with optional password
6. Warnings: expiration alerts (30/7/1 day), expired cert blocking with override option
7. CA Bundles: custom trust anchor management, system truststore integration

UI Requirements:
- Certificate list with status icons (valid/warning/expired)
- Detail panel with parsed fields and PEM display
- Import wizard with format detection
- Generation dialog with key type, size, and SAN configuration
- Notification area for expiration warnings
```

### 10.3 UI Component Prompts

```
Implement a reusable JavaFX component for NexusLink:

COMPONENT: [Name]
PURPOSE: [Description]

Requirements:
- FXML + Controller pattern
- CSS styling with theme variable support (dark/light)
- Keyboard navigation support
- Accessibility: screen reader labels, focus indicators
- Responsive layout (min/pref/max sizes)
- Event handling via JavaFX properties and event bus

Styling:
- Use CSS variables for colors (--primary, --accent, --success, etc.)
- Support dark and light themes via pseudo-class :dark-theme
- Font: Inter for UI, JetBrains Mono for code
- Border radius: 6px for cards, 4px for inputs
- Shadows: subtle drop shadows for elevated elements

Testing:
- TestFX UI tests for interaction flows
- Verify theme switching
- Verify keyboard accessibility
```

---

## 11. Appendix: Protocol Deep-Dives

### 11.1 Kafka Configuration Matrix

| Security Protocol | SASL Mechanism | Use Case |
|-------------------|---------------|----------|
| PLAINTEXT | None | Development only |
| SSL | None | TLS encryption, no auth |
| SASL_PLAINTEXT | PLAIN | Username/password (dev) |
| SASL_PLAINTEXT | SCRAM-SHA-256/512 | Username/password (prod) |
| SASL_PLAINTEXT | GSSAPI (Kerberos) | Enterprise Kerberos |
| SASL_SSL | PLAIN | TLS + username/password |
| SASL_SSL | SCRAM-SHA-256/512 | TLS + strong password auth |
| SASL_SSL | GSSAPI | TLS + Kerberos |
| SASL_SSL | OAUTHBEARER | TLS + OAuth 2.0 |
| SASL_SSL | AWS_MSK_IAM | AWS MSK IAM auth |
| SSL (mTLS) | None | Certificate-based auth |

### 11.2 JMS Provider Configuration

| Provider | Connection Factory Class | JNDI | Special Features |
|----------|-------------------------|------|-----------------|
| **ActiveMQ** | `org.apache.activemq.ActiveMQConnectionFactory` | Optional | OpenWire, failover URL |
| **Artemis** | `org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory` | Optional | Core protocol, HA |
| **IBM MQ** | `com.ibm.mq.jms.MQConnectionFactory` | Optional | Channel, QM name |
| **Solace** | `com.solacesystems.jms.SolConnectionFactory` | Optional | VPN, host list |
| **WebLogic** | `weblogic.jms.ConnectionFactory` | Required | JNDI lookup |
| **WebSphere** | `com.ibm.websphere.sib.api.jms.JmsConnectionFactory` | Required | SIBus |

### 11.3 MQTT v5.0 New Features

| Feature | Description | UI Impact |
|---------|-------------|-----------|
| **Shared Subscriptions** | `$share/{group}/{topic}` | Group name input field |
| **Topic Aliases** | Numeric topic aliases to reduce bandwidth | Auto-detection display |
| **User Properties** | Custom key-value properties in PUBLISH | Properties table editor |
| **Request/Response** | Correlation data + response topic | Request/response pattern UI |
| **Reason Codes** | Detailed reason codes for all packets | Enhanced error display |
| **Payload Format Indicator** | UTF-8 string vs. binary payload | Auto-detect toggle |
| **Content Type** | MIME type for payload | Content-Type dropdown |
| **Session Expiry** | Configurable session persistence | Session config panel |
| **Message Expiry** | Per-message TTL | Expiry input field |

### 11.4 SFTP Authentication Methods

| Method | Description | UI Requirements |
|--------|-------------|-----------------|
| **Password** | Username + password | Password field with reveal toggle |
| **Public Key** | Private key file + optional passphrase | Key file picker + passphrase field |
| **Keyboard-Interactive** | Server-prompted challenges | Dynamic prompt dialog |
| **Two-Factor** | Password + OTP/TOTP | Password + OTP code fields |
| **Agent** | SSH agent (Pageant/ssh-agent) | Agent detection + key selection |
| **Host-Based** | Host key authentication | Host key configuration |

### 11.5 OAuth 2.0 Flow Selection Guide

| Flow | When to Use | UI Configuration |
|------|-------------|-----------------|
| **Authorization Code + PKCE** | Mobile/SPA apps, most secure | Auth URL, Token URL, Client ID, Redirect URI, PKCE toggle |
| **Client Credentials** | Machine-to-machine | Token URL, Client ID, Client Secret, Scope |
| **Resource Owner Password** | Legacy/trusted devices | Token URL, Client ID, Username, Password |
| **Device Code** | Input-constrained devices | Device Auth URL, Token URL, Client ID |
| **Implicit** | Legacy SPAs (deprecated) | Auth URL, Client ID, Redirect URI |

---

## 12. Glossary

| Term | Definition |
|------|------------|
| **ALPN** | Application-Layer Protocol Negotiation — used in TLS to negotiate HTTP/2 |
| **DLQ** | Dead Letter Queue — queue for messages that cannot be processed |
| **JAAS** | Java Authentication and Authorization Service |
| **JPMS** | Java Platform Module System (Java 9+) |
| **JNDI** | Java Naming and Directory Interface |
| **mTLS** | Mutual TLS — both client and server present certificates |
| **MVVM** | Model-View-ViewModel — UI architecture pattern |
| **OCSP** | Online Certificate Status Protocol — real-time cert revocation check |
| **PKCE** | Proof Key for Code Exchange — OAuth 2.0 security extension |
| **RFH2** | Rules and Formatting Header 2 — IBM MQ message header |
| **SASL** | Simple Authentication and Security Layer |
| **SAN** | Subject Alternative Name — certificate field for multiple hostnames |
| **SPNEGO** | Simple and Protected GSSAPI Negotiation Mechanism — Kerberos over HTTP |
| **SPI** | Service Provider Interface — Java plugin mechanism |
| **TTFB** | Time To First Byte — network performance metric |
| **XA** | eXtended Architecture — distributed transaction standard |

---

*Document Version: 1.0.0*  
*Last Updated: 2026-06-23*  
*Framework: RouteForge*  
*Product: NexusLink*
