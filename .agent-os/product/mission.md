# Mission

## What NexusLink is

A single JavaFX desktop workbench for talking to **everything an enterprise system connects to** —
HTTP/REST, WebSocket, SSE, GraphQL, gRPC, Kafka, MQTT, RabbitMQ, JMS/IBM MQ/Solace, cloud
messaging, SFTP/FTP, S3/Azure/GCS, SQL databases, MongoDB, Redis, LDAP, SSH, SNMP, and LLM
endpoints — with one consistent interface, one credential vault, and one history.

The pitch is *replacement, not addition*: instead of Postman **and** DBeaver **and** a Kafka UI
**and** an S3 browser **and** an LDAP tool, one application that does each of those jobs at least as
well as the tool it replaces.

## Who it is for

Engineers, SREs and integration developers inside organisations — which is a specific constraint,
not a demographic note. It means the environment usually looks like this:

- **No direct internet access.** Dependencies come from an internal Artifactory/Nexus mirror,
  through an HTTP proxy, behind a corporate CA that terminates TLS.
- **Credentials are federated.** Access is an OIDC token from the company IdP, not a vendor API key.
- **Software installation is restricted.** The app must not need admin rights, a service, or a
  daemon, and it has to work when a download simply cannot happen.
- **Data is sensitive.** Nothing is phoned home; secrets are not written to disk in plaintext.

A feature that works only when the machine can reach the public internet is a feature that does not
work for this audience. This is the single most load-bearing idea in the product.

## What "done" means for a feature

1. It works in a locked-down network, or degrades to a path that does — and the UI says which.
2. Its failure message tells the user what to do next, naming the setting or file involved.
3. The logic that can be tested without infrastructure *is* tested without infrastructure.
4. It is documented in the in-app help, not only in the repository.
5. `TASKS.md` reflects it.

## Non-goals

- **Not a server.** No daemon, no hosted component, no accounts.
- **Not a telemetry product.** Usage reporting is opt-in and off.
- **Not a code editor or an IDE.** It edits requests and queries, not projects.
- **Not a scheduler.** Running things on a timer belongs to the systems it talks to.
