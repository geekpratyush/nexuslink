package com.nexuslink.protocol.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis client over Lettuce. Connect with a {@code redis://[:password@]host:port[/db]} (or
 * {@code rediss://} for TLS) URI, browse keys, read typed values, and run commands from a console.
 */
public final class RedisService implements AutoCloseable {

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;

    public void connect(String uri) {
        close();
        client = RedisClient.create(uri);
        connection = client.connect();
        redis = connection.sync();
        redis.ping(); // verify
    }

    public boolean isConnected() { return redis != null; }

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
    public String execute(String commandLine) {
        String[] p = commandLine.trim().split("\\s+");
        if (p.length == 0 || p[0].isEmpty()) return "";
        String cmd = p[0].toUpperCase();
        try {
            return switch (cmd) {
                case "PING" -> redis.ping();
                case "GET" -> nil(redis.get(p[1]));
                case "SET" -> redis.set(p[1], rest(p, 2));
                case "DEL" -> String.valueOf(redis.del(Arrays.copyOfRange(p, 1, p.length)));
                case "EXISTS" -> String.valueOf(redis.exists(Arrays.copyOfRange(p, 1, p.length)));
                case "TYPE" -> redis.type(p[1]);
                case "TTL" -> String.valueOf(redis.ttl(p[1]));
                case "EXPIRE" -> String.valueOf(redis.expire(p[1], Long.parseLong(p[2])));
                case "KEYS" -> String.join("\n", redis.keys(p.length > 1 ? p[1] : "*"));
                case "INCR" -> String.valueOf(redis.incr(p[1]));
                case "DECR" -> String.valueOf(redis.decr(p[1]));
                case "HSET" -> String.valueOf(redis.hset(p[1], p[2], rest(p, 3)));
                case "HGET" -> nil(redis.hget(p[1], p[2]));
                case "HGETALL" -> redis.hgetall(p[1]).toString();
                case "LPUSH" -> String.valueOf(redis.lpush(p[1], Arrays.copyOfRange(p, 2, p.length)));
                case "RPUSH" -> String.valueOf(redis.rpush(p[1], Arrays.copyOfRange(p, 2, p.length)));
                case "LRANGE" -> String.join("\n", redis.lrange(p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
                case "SADD" -> String.valueOf(redis.sadd(p[1], Arrays.copyOfRange(p, 2, p.length)));
                case "SMEMBERS" -> String.join("\n", redis.smembers(p[1]));
                case "DBSIZE" -> String.valueOf(redis.dbsize());
                default -> "(command not supported in console: " + cmd + ")";
            };
        } catch (Exception e) {
            return "ERR " + e.getMessage();
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
