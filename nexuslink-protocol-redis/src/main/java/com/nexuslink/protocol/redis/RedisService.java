package com.nexuslink.protocol.redis;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis client over Lettuce. Connect with a {@code redis://[:password@]host:port[/db]} (or
 * {@code rediss://} for TLS) URI, browse keys, read typed values, and run commands from a console.
 *
 * <p>Also speaks the two multi-node topologies (see {@link RedisTopology}): a
 * {@code redis-sentinel://} URI is parsed by Lettuce natively, and our own
 * {@code redis-cluster://h1:6379,h2:6379} seed-list scheme opens a {@link RedisClusterClient}. The
 * command surface is the same in every mode because the field is typed to
 * {@link RedisClusterCommands} — the common supertype that both standalone {@code RedisCommands} and
 * {@code RedisAdvancedClusterCommands} extend.
 */
public final class RedisService implements AutoCloseable {

    private AbstractRedisClient client;
    private StatefulConnection<String, String> connection;
    private RedisClusterCommands<String, String> redis;
    private RedisTopology topology = RedisTopology.STANDALONE;

    public void connect(String uri) {
        close();
        topology = RedisTopology.of(uri);
        if (topology == RedisTopology.CLUSTER) {
            List<RedisURI> seeds = RedisTopology.seedUris(uri).stream().map(RedisURI::create).toList();
            RedisClusterClient cluster = RedisClusterClient.create(seeds);
            client = cluster;
            var conn = cluster.connect();
            connection = conn;
            redis = conn.sync();
        } else {
            // Standalone and sentinel URIs are both understood by RedisClient directly.
            RedisClient standalone = RedisClient.create(uri);
            client = standalone;
            var conn = standalone.connect();
            connection = conn;
            redis = conn.sync();
        }
        redis.ping(); // verify
    }

    public boolean isConnected() { return redis != null; }

    /** The topology of the current (or most recent) connection. */
    public RedisTopology topology() { return topology; }

    /** SCAN keys matching {@code pattern} (default *), capped at {@code limit}. */
    public List<String> scanKeys(String pattern, int limit) {
        List<String> keys = new ArrayList<>();
        ScanArgs args = ScanArgs.Builder.matches(pattern == null || pattern.isBlank() ? "*" : pattern).limit(200);
        KeyScanCursor<String> cursor = redis.scan(args);
        while (true) {
            keys.addAll(cursor.getKeys());
            if (keys.size() >= limit || cursor.isFinished()) break;
            cursor = redis.scan(cursor, args);
        }
        return keys.size() > limit ? new ArrayList<>(keys.subList(0, limit)) : keys;
    }

    public String type(String key) { return redis.type(key); }

    public long ttl(String key) { return redis.ttl(key); }

    public long dbSize() { return redis.dbsize(); }

    /** Renders a key's value according to its Redis type. */
    public String value(String key) {
        String type = redis.type(key);
        return switch (type) {
            case "string" -> nil(redis.get(key));
            case "hash" -> redis.hgetall(key).entrySet().stream()
                    .map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining("\n"));
            case "list" -> String.join("\n", redis.lrange(key, 0, 500));
            case "set" -> String.join("\n", redis.smembers(key));
            case "zset" -> redis.zrangeWithScores(key, 0, 500).stream()
                    .map(sv -> sv.getScore() + "  " + sv.getValue()).collect(Collectors.joining("\n"));
            case "stream" -> redis.xrange(key, Range.create("-", "+")).stream()
                    .map(m -> m.getId() + "  " + m.getBody()).collect(Collectors.joining("\n"));
            case "none" -> "(key not found)";
            default -> "(unsupported type: " + type + ")";
        };
    }

    /** Runs a single command line from the console and returns a printable result. */
    /**
     * Publishes {@code message} to {@code channel}.
     *
     * @return the number of subscribers that received it
     */
    public long publish(String channel, String message) {
        return redis.publish(channel, message == null ? "" : message);
    }

    /** The channels with at least one subscriber, as reported by {@code PUBSUB CHANNELS}. */
    public List<String> activeChannels() {
        return redis.pubsubChannels();
    }

    /**
     * Runs one console command line against the server and renders the reply.
     *
     * <p>The command is <b>dispatched generically</b> rather than matched against a list we maintain:
     * the argument list goes to the server as typed, and the server decides what it accepts. That is
     * the only way a console can be correct across versions and flavours — {@code GETDEL} exists from
     * 6.2, {@code FUNCTION} from 7.0, {@code OBJECT FREQ} only under an LFU policy, {@code JSON.SET}
     * and {@code FT.SEARCH} only with the Redis Stack modules loaded, and managed services block
     * {@code CONFIG} and {@code DEBUG} outright. An unknown command comes back as the server's own
     * error, which says far more than a client-side "not supported" ever could.
     *
     * @return the reply rendered as text, or {@code ERR …} with the server's message
     */
    public String execute(String commandLine) {
        List<String> args;
        try {
            args = RedisCommandLine.parse(commandLine);
        } catch (RedisCommandLine.RedisCommandLineException e) {
            return "ERR " + e.getMessage();
        }
        if (args.isEmpty()) return "";
        if (redis == null) return "ERR not connected";

        String name = args.get(0).toUpperCase(java.util.Locale.ROOT);
        try {
            CommandArgs<String, String> commandArgs = new CommandArgs<>(StringCodec.UTF8);
            // A multi-word command (CONFIG GET, OBJECT ENCODING, CLIENT LIST, XINFO STREAM) is one
            // keyword plus arguments on the wire, so everything after the name is just an argument.
            for (int i = 1; i < args.size(); i++) commandArgs.add(args.get(i));
            Object reply = redis.dispatch(keyword(name), new RedisReplyOutput(StringCodec.UTF8), commandArgs);
            return render(reply);
        } catch (Exception e) {
            String message = e.getMessage();
            String rendered = message == null ? "ERR " + e.getClass().getSimpleName()
                    : (message.startsWith("ERR") || message.contains("ERR ") ? message : "ERR " + message);
            // "unknown command" is usually a version or flavour gap, not a typo — say which.
            if (rendered.toLowerCase(java.util.Locale.ROOT).contains("unknown command")) {
                String hint = serverInfo().versionHint(name);
                if (!hint.isEmpty()) rendered = rendered + "\n(" + hint + ")";
            }
            return rendered;
        }
    }

    /**
     * A command keyword for any name — including module commands like {@code JSON.SET} that Lettuce's
     * built-in {@link CommandType} enum has never heard of.
     */
    private static ProtocolKeyword keyword(String name) {
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new ProtocolKeyword() {
            @Override public byte[] getBytes() { return bytes; }
            @Override public String name() { return name; }
            @Override public String toString() { return name; }
        };
    }

    /**
     * Renders a reply the way {@code redis-cli} does: a nil as {@code (nil)}, a list one element per
     * line, a map as {@code key = value}, and anything else as its text.
     */
    private static String render(Object reply) {
        if (reply == null) return "(nil)";
        if (reply instanceof List<?> list) {
            if (list.isEmpty()) return "(empty list or set)";
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(render(o).replace("\n", "\n  "));
            }
            return sb.toString();
        }
        if (reply instanceof java.util.Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            map.forEach((k, v) -> {
                if (sb.length() > 0) sb.append('\n');
                sb.append(k).append(" = ").append(render(v));
            });
            return sb.toString();
        }
        return String.valueOf(reply);
    }

    /**
     * The server's own description of itself, from {@code INFO} — version, mode and flavour. Used to
     * label the connection and to explain a command the server does not have.
     */
    public RedisServerInfo serverInfo() {
        if (redis == null) return RedisServerInfo.unknown();
        try {
            return RedisServerInfo.parse(String.valueOf(
                    redis.dispatch(keyword("INFO"), new RedisReplyOutput(StringCodec.UTF8),
                            new CommandArgs<>(StringCodec.UTF8).add("server"))));
        } catch (Exception e) {
            return RedisServerInfo.unknown();
        }
    }

    private static String rest(String[] parts, int from) {
        return String.join(" ", Arrays.copyOfRange(parts, from, parts.length));
    }

    private static String nil(String s) { return s == null ? "(nil)" : s; }

    @Override
    public void close() {
        redis = null;
        if (connection != null) { try { connection.close(); } catch (Exception ignored) { } connection = null; }
        if (client != null) { try { client.shutdown(); } catch (Exception ignored) { } client = null; }
    }
}
