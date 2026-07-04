# NexusLink local test environment

A self-contained, **all-open-source** Docker Compose stack that runs one real server per protocol
module, so NexusLink's protocol clients can be exercised end-to-end on a laptop — no cloud accounts,
no licences, no external endpoints.

## Licensing

This directory only *references* upstream images to run them as local test fixtures. It does not
redistribute them, and NexusLink links to each service exclusively through its permissively licensed
**client library** (Apache/MIT/BSD JDBC drivers and SDKs), never the server binary. So none of the
images' licences — including MongoDB's SSPL or MinIO/LocalStack's AGPL — attach to the NexusLink
codebase. Everything here is safe to ship in an open-source project.

## Start / stop

```bash
# from the repo root
docker compose -f test-env/docker-compose.yml up -d           # start everything
docker compose -f test-env/docker-compose.yml ps              # check health
docker compose -f test-env/docker-compose.yml --profile extra up -d   # + Apicurio schema registry
docker compose -f test-env/docker-compose.yml down -v         # stop + wipe volumes
```

## Services & endpoints (all creds are throwaway local values)

| Module | Service | Image (licence) | Endpoint | Credentials |
|---|---|---|---|---|
| protocol-db | PostgreSQL | postgres:16-alpine (PostgreSQL) | `jdbc:postgresql://localhost:5432/nexus` | nexus / nexus |
| protocol-db | MariaDB | mariadb:11 (GPLv2) | `jdbc:mariadb://localhost:3306/nexus` | nexus / nexus |
| protocol-mongo | MongoDB | mongo:7 (SSPL) | `mongodb://localhost:27017` | — |
| protocol-redis | Redis | redis:7-alpine (BSD) | `redis://localhost:6379` | — |
| protocol-kafka | Kafka (KRaft) | apache/kafka:3.9.0 (Apache-2.0) | `localhost:9092` | — |
| protocol-kafka | Schema Registry | apicurio-registry-mem:2.6.2 (Apache-2.0) | `http://localhost:8081/apis/ccompat/v7` | — (profile `extra`) |
| protocol-rabbitmq | RabbitMQ | rabbitmq:3-management (MPL-2.0) | `localhost:5672`, UI `http://localhost:15672` | nexus / nexus |
| protocol-mqtt | Mosquitto | eclipse-mosquitto:2 (EPL/EDL) | `tcp://localhost:1883`, ws `9001` | anonymous |
| protocol-ldap | OpenLDAP | osixia/openldap:1.5.0 | `ldap://localhost:389` | `cn=admin,dc=nexuslink,dc=dev` / admin |
| protocol-snmp | net-snmp | polinux/snmpd (BSD) | `udp://localhost:1161` | community `public` |
| protocol-sftp | SFTP | atmoz/sftp (MIT) | `sftp://localhost:2222` | nexus / nexus |
| protocol-ftp | FTP | delfer/alpine-ftp-server (MIT) | `ftp://localhost:21` (passive) | nexus / nexus123 |
| protocol-s3 | S3 | localstack:3 (Apache-2.0) | `http://localhost:4566` (path-style) | test / test |
| protocol-sqs | SQS + SNS | localstack:3 (Apache-2.0) | `http://localhost:4566` | test / test |
| protocol-azure | Azure Blob | azurite (MIT) | `http://localhost:10000` | devstoreaccount1 (well-known) |
| protocol-gcs | GCS | fake-gcs-server (BSD) | `http://localhost:4443` | anonymous (emulator) |
| protocol-http | REST target | mccutchen/go-httpbin (MIT) | `http://localhost:8088` | — |

The LDAP directory is seeded (`ldap/seed.ldif`) with `uid=alice` / `uid=bob` under
`ou=people,dc=nexuslink,dc=dev` and a `cn=engineers` group.

## Running the live integration tests

Every module ships a `*LiveIT` gated on `-Dnexuslink.it=true`, so the normal build
(`mvn test`) stays green without the stack. With the stack up:

```bash
# all live ITs
mvn test -Dnexuslink.it=true -Dtest='*LiveIT' -DfailIfNoTests=false

# GCS additionally needs the emulator host exported:
STORAGE_EMULATOR_HOST=http://localhost:4443 \
  mvn -pl nexuslink-protocol-gcs test -Dnexuslink.it=true -Dtest=GcsLiveIT

# MongoDB uses Testcontainers (spins up its own mongo):
mvn -pl nexuslink-protocol-mongo test -DrunMongoIT=true
```

Or use the helper: `./test-env/run-live-its.sh` (brings the stack up, runs every LiveIT, reports).

## Covered end-to-end

JDBC (Postgres + MariaDB), Redis, Kafka (produce→consume), RabbitMQ (publish→consume),
MQTT (subscribe→publish), LDAP (bind/search), SNMP (GET + WALK), S3, Azure Blob, GCS, SFTP, FTP,
and REST (GET/POST/basic-auth) — plus MongoDB via Testcontainers.
