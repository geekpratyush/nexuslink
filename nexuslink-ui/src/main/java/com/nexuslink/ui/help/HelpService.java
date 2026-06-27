package com.nexuslink.ui.help;

import com.nexuslink.core.cache.CacheRegistry;
import com.nexuslink.core.cache.CacheRegion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Singleton service owning the help index and content loading.
 * Initialised once at startup; search results are Caffeine-cached.
 */
public final class HelpService {

    private static final HelpService INSTANCE = new HelpService();

    private final HelpIndex index = new HelpIndex();
    private final CacheRegion<String, List<SearchResult>> searchCache;
    private final List<String> recentTopics = new CopyOnWriteArrayList<>();

    /** Maps UI component ID prefix → help topic anchor (for context-sensitive F1). */
    private final Map<String, String> contextMap = new HashMap<>();

    private HelpService() {
        searchCache = CacheRegistry.get().region(CacheRegistry.HELP_SEARCH);
        registerContextMappings();
        loadAllTopics();
    }

    public static HelpService get() {
        return INSTANCE;
    }

    /** Returns cached search results — fast even on repeated queries. */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String cacheKey = query.toLowerCase().strip();
        return searchCache.getOrLoad(cacheKey, k -> index.search(k));
    }

    /**
     * Returns the topic + anchor to open when the user presses F1 on a component.
     * @param componentId JavaFX node ID (or prefix, e.g. "authTab", "kafkaProducer")
     */
    public Optional<String> contextTarget(String componentId) {
        if (componentId == null) return Optional.empty();
        // Exact match first, then prefix match
        String target = contextMap.get(componentId);
        if (target != null) return Optional.of(target);
        return contextMap.entrySet().stream()
                .filter(e -> componentId.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public Collection<HelpTopic> allTopics() {
        return index.allTopics();
    }

    public Optional<HelpTopic> topic(String id) {
        return index.topic(id);
    }

    /** Load the raw Markdown content for a topic. */
    public String loadContent(String topicId) {
        return topic(topicId).map(t -> {
            try (InputStream in = HelpService.class.getResourceAsStream(t.resourcePath())) {
                if (in == null) return "# Content not found\n\nHelp file missing: " + t.resourcePath();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            } catch (IOException e) {
                return "# Error loading help\n\n" + e.getMessage();
            }
        }).orElse("# Topic not found\n\nNo help available for: " + topicId);
    }

    /** Track recently viewed topics (capped at 10). */
    public void recordViewed(String topicId) {
        recentTopics.remove(topicId);
        recentTopics.add(0, topicId);
        if (recentTopics.size() > 10) recentTopics.subList(10, recentTopics.size()).clear();
    }

    public List<String> recentTopics() {
        return Collections.unmodifiableList(recentTopics);
    }

    // ---- private ----

    private void loadAllTopics() {
        // Each entry: id, title, category, resourcePath, keywords
        List<Object[]> topicDefs = List.of(
            new Object[]{"getting-started",    "Getting Started",         "General",          "/com/nexuslink/ui/help/getting-started.md",    List.of("first run", "tutorial", "quick start")},
            new Object[]{"rest-client",        "REST Client",             "HTTP",             "/com/nexuslink/ui/help/rest-client.md",         List.of("http", "get", "post", "put", "delete", "request", "response", "headers", "body", "auth")},
            new Object[]{"kafka-client",       "Kafka Client",            "Messaging",        "/com/nexuslink/ui/help/kafka-client.md",        List.of("producer", "consumer", "topic", "schema registry", "consumer group", "offset", "ksqldb")},
            new Object[]{"security",           "Security & Authentication","Security",        "/com/nexuslink/ui/help/security.md",            List.of("tls", "mtls", "oauth", "kerberos", "saml", "certificate", "vault", "credentials")},
            new Object[]{"certificate-manager","Certificate Manager",      "Security",        "/com/nexuslink/ui/help/certificate-manager.md", List.of("x509", "pem", "pkcs12", "jks", "ssl", "tls", "cert", "expiry")},
            new Object[]{"environment-vars",   "Environment Variables",    "General",         "/com/nexuslink/ui/help/environment-vars.md",    List.of("env", "variable", "substitution", "dotenv", ".env", "profile")},
            new Object[]{"keyboard-shortcuts", "Keyboard Shortcuts",       "General",         "/com/nexuslink/ui/help/keyboard-shortcuts.md",  List.of("hotkey", "keybinding", "ctrl", "shortcut", "f1")},
            new Object[]{"code-generation",    "Code Generation",          "General",         "/com/nexuslink/ui/help/code-generation.md",     List.of("curl", "java", "python", "go", "javascript", "generate", "snippet")},
            new Object[]{"mqtt",               "MQTT Client",              "Messaging",       "/com/nexuslink/ui/help/mqtt.md",                List.of("paho", "qos", "retain", "will", "subscribe", "publish", "mqtt5")},
            new Object[]{"rabbitmq",           "RabbitMQ Client",          "Messaging",       "/com/nexuslink/ui/help/rabbitmq.md",            List.of("amqp", "exchange", "queue", "binding", "routing key", "fanout", "topic", "consumer")},
            new Object[]{"grpc",               "gRPC Client",              "HTTP",            "/com/nexuslink/ui/help/grpc.md",                List.of("protobuf", "proto", "streaming", "unary", "reflection", "grpc")},
            new Object[]{"graphql",            "GraphQL Client",           "HTTP",            "/com/nexuslink/ui/help/graphql.md",             List.of("query", "mutation", "subscription", "introspection", "schema", "variables")},
            new Object[]{"sftp",               "SFTP / File Transfer",     "File Transfer",   "/com/nexuslink/ui/help/sftp.md",                List.of("ssh", "scp", "file", "upload", "download", "sync", "transfer")},
            new Object[]{"databases",          "Database Clients",         "Database",        "/com/nexuslink/ui/help/databases.md",           List.of("sql", "jdbc", "redis", "mongodb", "query", "schema")},
            new Object[]{"ldap",               "LDAP / Active Directory",  "Enterprise",      "/com/nexuslink/ui/help/ldap.md",                List.of("active directory", "ad", "ldap", "directory", "search", "bind")},
            new Object[]{"snmp",               "SNMP Browser",             "Enterprise",      "/com/nexuslink/ui/help/snmp.md",                List.of("mib", "oid", "trap", "walk", "snmpv3")},
            new Object[]{"troubleshooting",    "Troubleshooting",          "General",         "/com/nexuslink/ui/help/troubleshooting.md",     List.of("error", "fix", "problem", "connection refused", "ssl handshake", "timeout", "auth failed")},
            new Object[]{"plugins",            "Plugin Development",       "Advanced",        "/com/nexuslink/ui/help/plugins.md",             List.of("spi", "extension", "custom", "protocol", "plugin api")},
            new Object[]{"agent",              "AI Agent (MCP tools)",     "AI",              "/com/nexuslink/ui/help/agent.md",               List.of("agent", "mcp", "tool use", "tool calling", "claude", "anthropic", "loop", "tools")},
            new Object[]{"metrics",            "Metrics Dashboard",        "General",         "/com/nexuslink/ui/help/metrics.md",             List.of("metrics", "monitoring", "throughput", "latency", "percentile", "p95", "p99", "error rate", "dashboard")}
        );

        for (Object[] def : topicDefs) {
            HelpTopic topic = new HelpTopic(
                    (String) def[0],
                    (String) def[1],
                    (String) def[2],
                    (String) def[3],
                    List.of(), // sections populated lazily or pre-built
                    (List<String>) def[4]
            );
            index.index(topic);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerContextMappings() {
        // Format: componentId prefix → "topicId" or "topicId#anchor"
        contextMap.put("restMethod",           "rest-client#methods");
        contextMap.put("urlBar",               "rest-client#url-bar");
        contextMap.put("authTab",              "security#http-auth");
        contextMap.put("oauthFlow",            "security#oauth-flows");
        contextMap.put("certSelector",         "certificate-manager");
        contextMap.put("kafkaBootstrap",       "kafka-client#connection");
        contextMap.put("kafkaSasl",            "kafka-client#security");
        contextMap.put("kafkaProducer",        "kafka-client#producer");
        contextMap.put("kafkaConsumer",        "kafka-client#consumer");
        contextMap.put("schemaRegistry",       "kafka-client#schema-registry");
        contextMap.put("consumerLag",          "kafka-client#consumer-groups");
        contextMap.put("mqttBroker",           "mqtt#connection");
        contextMap.put("mqttQos",              "mqtt#qos");
        contextMap.put("grpcProto",            "grpc#loading-proto");
        contextMap.put("graphqlIntrospect",    "graphql#introspection");
        contextMap.put("sftpAuth",             "sftp#authentication");
        contextMap.put("envEditor",            "environment-vars");
        contextMap.put("vaultMaster",          "security#credential-vault");
        contextMap.put("codeGenTab",           "code-generation");
    }
}
