package com.nexuslink.ui.main;

import java.util.Map;

/**
 * The searchable text behind each connection type in the sidebar's "Filter connection types" box.
 *
 * <p>A type's own label is rarely the word someone reaches for — people look for "postgres", not
 * "SQL Client"; "queue", not "IBM MQ"; "bucket", not "S3 / Object Storage". This class pairs each
 * protocol id with those everyday words so the filter finds the right button on the first try, and
 * keeps them out of {@link MainWindow}'s layout code where they cannot be tested.
 */
final class ProtocolSearch {

    /** Extra search words per protocol id; a type with none listed is found by its label alone. */
    private static final Map<String, String> KEYWORDS = Map.ofEntries(
            Map.entry("rest", "http api request postman curl"),
            Map.entry("ws", "websocket socket realtime"),
            Map.entry("sse", "server sent events stream"),
            Map.entry("graphql", "gql query api"),
            Map.entry("grpc", "protobuf proto rpc"),
            Map.entry("sql", "database jdbc postgres postgresql mysql oracle sqlite mariadb h2 db2 query"),
            Map.entry("mongo", "mongodb database nosql document collection"),
            Map.entry("s3", "object storage bucket aws minio wasabi"),
            Map.entry("azure", "object storage container bucket blob microsoft"),
            Map.entry("gcs", "object storage bucket google"),
            Map.entry("sftp", "file transfer ssh scp"),
            Map.entry("ftp", "file transfer ftps"),
            Map.entry("kafka", "broker topic queue stream event confluent redpanda"),
            Map.entry("mqtt", "broker topic queue iot mosquitto"),
            Map.entry("rabbitmq", "broker queue amqp exchange"),
            Map.entry("jms", "broker queue activemq artemis"),
            Map.entry("ibmmq", "broker queue websphere mqseries"),
            Map.entry("solace", "broker queue topic pubsub"),
            Map.entry("sqs", "broker queue sns aws"),
            Map.entry("pubsub", "broker queue topic google"),
            Map.entry("servicebus", "broker queue topic azure microsoft"),
            Map.entry("redis", "cache database key value valkey"),
            Map.entry("ldap", "directory active ad bind dn"),
            Map.entry("snmp", "network monitoring mib trap"),
            Map.entry("ssh", "terminal shell console remote"),
            Map.entry("mcp", "model context protocol tools ai"),
            Map.entry("llm", "ai claude anthropic model prompt"),
            Map.entry("agent", "ai claude tools mcp"));

    private ProtocolSearch() {}

    /** The extra search words for a protocol id, or an empty string when it has none. */
    static String keywords(String id) {
        return KEYWORDS.getOrDefault(id, "");
    }

    /** The text the filter box matches against: the type's label, its id, and its everyday words. */
    static String haystack(String id, String label) {
        return label + " " + id + " " + keywords(id);
    }
}
