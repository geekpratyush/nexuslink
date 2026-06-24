package com.nexuslink.core.connection;

import java.util.ArrayList;
import java.util.List;

import static com.nexuslink.core.connection.ConnectionProfile.Protocol.*;

/**
 * A bundled, clearly-labelled catalog of <em>public</em> test endpoints so users can connect and
 * try NexusLink immediately. These are well-known public/demo services (or harmless localhost
 * defaults) — not corporate endpoints — and every entry is hideable via {@link ConnectionStore}
 * so organisations that don't want sample data can clear them.
 */
public final class SampleCatalog {

    private SampleCatalog() {}

    /** All bundled samples, in display order. */
    public static List<ConnectionProfile> all() {
        List<ConnectionProfile> s = new ArrayList<>();

        // ---- REST (free public test APIs) ----
        s.add(rest("sample-httpbin", "httpbin — HTTP test service", "https://httpbin.org/get"));
        s.add(rest("sample-jsonplaceholder", "JSONPlaceholder — fake REST", "https://jsonplaceholder.typicode.com/todos/1"));
        s.add(rest("sample-postman-echo", "Postman Echo", "https://postman-echo.com/get?foo=bar"));
        s.add(rest("sample-restcountries", "REST Countries", "https://restcountries.com/v3.1/name/germany"));
        s.add(rest("sample-open-meteo", "Open-Meteo — weather (no key)", "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m"));
        s.add(rest("sample-github", "GitHub REST API", "https://api.github.com/repos/openjdk/jdk"));
        s.add(rest("sample-swapi", "Star Wars API (SWAPI)", "https://swapi.dev/api/people/1"));

        // ---- WebSocket (public echo) ----
        s.add(ws("sample-ws-postman", "Postman Echo (WebSocket)", "wss://ws.postman-echo.com/raw"));
        s.add(ws("sample-ws-events", "websocket.events echo", "wss://echo.websocket.events"));

        // ---- GraphQL (public, no key) ----
        s.add(roadmap("sample-gql-countries", "Countries GraphQL", GRAPHQL,
                "https://countries.trevorblades.com/", "Public GraphQL API. GraphQL client is on the roadmap."));
        s.add(roadmap("sample-gql-rickmorty", "Rick & Morty GraphQL", GRAPHQL,
                "https://rickandmortyapi.com/graphql", "Public GraphQL API. GraphQL client is on the roadmap."));

        // ---- gRPC / SSE (public test servers) ----
        s.add(roadmap("sample-grpc-grpcbin", "grpcb.in — public gRPC", GRPC,
                "grpcb.in:9000", "Public gRPC test server (reflection enabled). gRPC client is on the roadmap."));
        s.add(roadmap("sample-sse-wikimedia", "Wikimedia recent changes (SSE)", SSE,
                "https://stream.wikimedia.org/v2/stream/recentchange",
                "Public Server-Sent-Events firehose. SSE client is on the roadmap."));

        // ---- SQL (documented public read-only databases) ----
        s.add(new ConnectionProfile("EBI RNAcentral — public PostgreSQL", SQL,
                    "jdbc:postgresql://hh-pgsql-public.ebi.ac.uk:5432/pfmegrnargs")
                .withUser("reader").withAuth(AuthMethod.BASIC)
                .prop("driverId", "postgresql").authProp("password", "NWDMCE5xdipIjRrp")
                .withNotes("Public read-only PostgreSQL (EBI RNAcentral). Credentials are published by EBI.")
                .asSample().withId("sample-pg-rnacentral"));
        s.add(new ConnectionProfile("EBI Rfam — public MySQL", SQL,
                    "jdbc:mysql://mysql-rfam-public.ebi.ac.uk:4497/Rfam")
                .withUser("rfamro").withAuth(AuthMethod.BASIC)
                .prop("driverId", "mysql")
                .withNotes("Public read-only MySQL (EBI Rfam), no password.")
                .asSample().withId("sample-mysql-rfam"));

        // ---- MongoDB (local default) ----
        s.add(new ConnectionProfile("Local MongoDB", MONGO, "mongodb://localhost:27017")
                .withAuth(AuthMethod.NONE)
                .withNotes("Local default. Use mongodb+srv://… with TLS/SCRAM for Atlas or enterprise.")
                .asSample().withId("sample-mongo-local"));

        // ---- MCP (reference server over stdio) ----
        s.add(new ConnectionProfile("MCP 'Everything' reference server", MCP,
                    "npx -y @modelcontextprotocol/server-everything")
                .withAuth(AuthMethod.NONE)
                .prop("transport", "stdio")
                .withNotes("Runs the reference MCP server locally via npx (requires Node).")
                .asSample().withId("sample-mcp-everything"));

        // ---- LLM ----
        s.add(new ConnectionProfile("Anthropic Claude (LLM tester)", LLM, "claude-opus-4-8")
                .withAuth(AuthMethod.API_KEY)
                .authProp("envVar", "ANTHROPIC_API_KEY")
                .withNotes("Set ANTHROPIC_API_KEY in the environment to make live calls.")
                .asSample().withId("sample-llm-anthropic"));

        // ---- Object storage (S3-compatible) ----
        s.add(new ConnectionProfile("MinIO Play — public S3 sandbox", S3, "https://play.min.io")
                .withUser("Q3AM3UQ867SPQQA43P2F").withAuth(AuthMethod.AWS_SIGV4)
                .authProp("secretKey", "zuf+tfteSlswRu7BJ86wekitnifILbZam1KYY3TG")
                .withNotes("Public S3-compatible sandbox by MinIO (credentials published by MinIO). "
                        + "Object-storage browser is on the roadmap.")
                .asSample().withId("sample-s3-minio-play"));

        // ---- Azure Blob (local Azurite emulator — well-known public dev credentials) ----
        s.add(new ConnectionProfile("Azurite (local Azure Blob emulator)", AZURE_BLOB,
                    "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                  + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                  + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;")
                .withAuth(AuthMethod.CONNECTION_STRING)
                .withNotes("Local Azurite emulator with the standard public dev credentials. "
                        + "Swap in your own connection string for a real account.")
                .asSample().withId("sample-azure-azurite"));

        // ---- File transfer (public demo servers) ----
        s.add(new ConnectionProfile("Rebex — public SFTP demo", SFTP, "sftp://test.rebex.net:22")
                .withUser("demo").withAuth(AuthMethod.BASIC).authProp("password", "password")
                .withNotes("Public read-only demo SFTP server (Rebex). SFTP connector is on the roadmap.")
                .asSample().withId("sample-sftp-rebex"));
        s.add(new ConnectionProfile("Rebex — public FTP demo", FTP, "ftp://test.rebex.net:21")
                .withUser("demo").withAuth(AuthMethod.BASIC).authProp("password", "password")
                .withNotes("Public read-only demo FTP/FTPS server (Rebex). FTP connector is on the roadmap.")
                .asSample().withId("sample-ftp-rebex"));

        // ---- Messaging / cache (local defaults) ----
        s.add(new ConnectionProfile("Local Kafka", KAFKA, "localhost:9092")
                .withAuth(AuthMethod.NONE)
                .withNotes("Local broker default. Kafka connector is on the roadmap (supports SASL/SCRAM, mTLS).")
                .asSample().withId("sample-kafka-local"));
        s.add(new ConnectionProfile("Local Redis", REDIS, "redis://localhost:6379")
                .withAuth(AuthMethod.NONE)
                .withNotes("Local Redis default. Redis client is on the roadmap (TLS/ACL supported).")
                .asSample().withId("sample-redis-local"));

        return s;
    }

    private static ConnectionProfile roadmap(String id, String name,
                                             ConnectionProfile.Protocol protocol, String target, String notes) {
        return new ConnectionProfile(name, protocol, target).withAuth(AuthMethod.NONE)
                .withNotes(notes).asSample().withId(id);
    }

    private static ConnectionProfile rest(String id, String name, String url) {
        return new ConnectionProfile(name, REST, url).withAuth(AuthMethod.NONE)
                .withNotes("Public test API.").asSample().withId(id);
    }

    private static ConnectionProfile ws(String id, String name, String url) {
        return new ConnectionProfile(name, WEBSOCKET, url).withAuth(AuthMethod.NONE)
                .withNotes("Public echo server.").asSample().withId(id);
    }
}
