package com.nexuslink.protocol.redis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A curated, dependency-free catalog of common Redis commands with light metadata, plus a
 * case-insensitive prefix matcher that backs the command console's auto-complete.
 *
 * <p>The catalog is a fixed, immutable list built once at class load. Every accessor is pure and
 * performs no I/O; returned lists are unmodifiable. Command names are stored UPPERCASE and grouped
 * by their Redis command group ({@code string}, {@code list}, {@code hash}, {@code set},
 * {@code zset}, {@code key}, {@code pubsub}, {@code server}, {@code connection}, {@code scripting},
 * {@code transaction}).
 */
public final class RedisCommandCatalog {

    /**
     * A single catalog entry.
     *
     * @param name    the command name, UPPERCASE (e.g. {@code SET})
     * @param group   the command group (e.g. {@code string})
     * @param summary a one-line description of what the command does
     * @param syntax  a short usage hint (e.g. {@code SET key value [EX seconds]})
     */
    public record Command(String name, String group, String summary, String syntax) {}

    private static final Comparator<Command> BY_NAME = Comparator.comparing(Command::name);

    private static final List<Command> CATALOG = build();

    private RedisCommandCatalog() {}

    private static List<Command> build() {
        List<Command> c = new ArrayList<>();

        // ---- string ----
        c.add(new Command("GET", "string", "Get the value of a key", "GET key"));
        c.add(new Command("SET", "string", "Set the string value of a key", "SET key value [EX seconds] [NX|XX]"));
        c.add(new Command("INCR", "string", "Increment the integer value of a key by one", "INCR key"));
        c.add(new Command("DECR", "string", "Decrement the integer value of a key by one", "DECR key"));
        c.add(new Command("APPEND", "string", "Append a value to a key", "APPEND key value"));
        c.add(new Command("GETSET", "string", "Set a key and return its old value", "GETSET key value"));
        c.add(new Command("MGET", "string", "Get the values of multiple keys", "MGET key [key ...]"));
        c.add(new Command("MSET", "string", "Set multiple keys to multiple values", "MSET key value [key value ...]"));

        // ---- key ----
        c.add(new Command("DEL", "key", "Delete one or more keys", "DEL key [key ...]"));
        c.add(new Command("EXISTS", "key", "Determine whether one or more keys exist", "EXISTS key [key ...]"));
        c.add(new Command("EXPIRE", "key", "Set a key's time to live in seconds", "EXPIRE key seconds"));
        c.add(new Command("TTL", "key", "Get the remaining time to live of a key", "TTL key"));
        c.add(new Command("KEYS", "key", "Find all keys matching a glob pattern", "KEYS pattern"));
        c.add(new Command("SCAN", "key", "Incrementally iterate the key space", "SCAN cursor [MATCH pattern] [COUNT n]"));
        c.add(new Command("TYPE", "key", "Determine the type stored at a key", "TYPE key"));
        c.add(new Command("RENAME", "key", "Rename a key", "RENAME key newkey"));
        c.add(new Command("PERSIST", "key", "Remove the expiration from a key", "PERSIST key"));

        // ---- list ----
        c.add(new Command("LPUSH", "list", "Prepend one or more values to a list", "LPUSH key value [value ...]"));
        c.add(new Command("RPUSH", "list", "Append one or more values to a list", "RPUSH key value [value ...]"));
        c.add(new Command("LPOP", "list", "Remove and get the first element of a list", "LPOP key [count]"));
        c.add(new Command("RPOP", "list", "Remove and get the last element of a list", "RPOP key [count]"));
        c.add(new Command("LRANGE", "list", "Get a range of elements from a list", "LRANGE key start stop"));
        c.add(new Command("LLEN", "list", "Get the length of a list", "LLEN key"));

        // ---- hash ----
        c.add(new Command("HSET", "hash", "Set the value of one or more hash fields", "HSET key field value [field value ...]"));
        c.add(new Command("HGET", "hash", "Get the value of a hash field", "HGET key field"));
        c.add(new Command("HDEL", "hash", "Delete one or more hash fields", "HDEL key field [field ...]"));
        c.add(new Command("HGETALL", "hash", "Get all fields and values of a hash", "HGETALL key"));
        c.add(new Command("HKEYS", "hash", "Get all field names in a hash", "HKEYS key"));
        c.add(new Command("HVALS", "hash", "Get all values in a hash", "HVALS key"));

        // ---- set ----
        c.add(new Command("SADD", "set", "Add one or more members to a set", "SADD key member [member ...]"));
        c.add(new Command("SREM", "set", "Remove one or more members from a set", "SREM key member [member ...]"));
        c.add(new Command("SMEMBERS", "set", "Get all the members of a set", "SMEMBERS key"));
        c.add(new Command("SCARD", "set", "Get the number of members in a set", "SCARD key"));
        c.add(new Command("SISMEMBER", "set", "Determine whether a value is a set member", "SISMEMBER key member"));

        // ---- zset ----
        c.add(new Command("ZADD", "zset", "Add members to a sorted set, or update their scores", "ZADD key score member [score member ...]"));
        c.add(new Command("ZRANGE", "zset", "Return a range of members in a sorted set", "ZRANGE key start stop [WITHSCORES]"));
        c.add(new Command("ZSCORE", "zset", "Get the score of a member in a sorted set", "ZSCORE key member"));
        c.add(new Command("ZRANK", "zset", "Determine the index of a member in a sorted set", "ZRANK key member"));
        c.add(new Command("ZREM", "zset", "Remove one or more members from a sorted set", "ZREM key member [member ...]"));

        // ---- pubsub ----
        c.add(new Command("SUBSCRIBE", "pubsub", "Subscribe to one or more channels", "SUBSCRIBE channel [channel ...]"));
        c.add(new Command("UNSUBSCRIBE", "pubsub", "Unsubscribe from channels", "UNSUBSCRIBE [channel ...]"));
        c.add(new Command("PUBLISH", "pubsub", "Post a message to a channel", "PUBLISH channel message"));
        c.add(new Command("PSUBSCRIBE", "pubsub", "Subscribe to channels matching patterns", "PSUBSCRIBE pattern [pattern ...]"));

        // ---- connection ----
        c.add(new Command("PING", "connection", "Ping the server", "PING [message]"));
        c.add(new Command("ECHO", "connection", "Echo the given string", "ECHO message"));
        c.add(new Command("SELECT", "connection", "Change the selected database", "SELECT index"));
        c.add(new Command("AUTH", "connection", "Authenticate to the server", "AUTH [username] password"));

        // ---- server ----
        c.add(new Command("INFO", "server", "Get information and statistics about the server", "INFO [section]"));
        c.add(new Command("DBSIZE", "server", "Return the number of keys in the current database", "DBSIZE"));
        c.add(new Command("FLUSHDB", "server", "Remove all keys from the current database", "FLUSHDB [ASYNC|SYNC]"));
        c.add(new Command("FLUSHALL", "server", "Remove all keys from all databases", "FLUSHALL [ASYNC|SYNC]"));
        c.add(new Command("CONFIG", "server", "Get or set server configuration parameters", "CONFIG GET|SET parameter [value]"));
        c.add(new Command("CLIENT", "server", "Inspect and manage client connections", "CLIENT subcommand [args ...]"));

        // ---- transaction ----
        c.add(new Command("MULTI", "transaction", "Mark the start of a transaction block", "MULTI"));
        c.add(new Command("EXEC", "transaction", "Execute all commands issued after MULTI", "EXEC"));
        c.add(new Command("DISCARD", "transaction", "Discard all commands issued after MULTI", "DISCARD"));
        c.add(new Command("WATCH", "transaction", "Watch keys for changes before a transaction", "WATCH key [key ...]"));

        // ---- scripting ----
        c.add(new Command("EVAL", "scripting", "Execute a Lua script server-side", "EVAL script numkeys [key ...] [arg ...]"));

        c.sort(BY_NAME);
        return List.copyOf(c);
    }

    /** The full catalog, sorted by name, unmodifiable. */
    public static List<Command> all() {
        return CATALOG;
    }

    /**
     * Case-insensitive prefix match on the command name. A blank or {@code null} prefix returns the
     * whole catalog. Only the first whitespace-delimited token of {@code prefix} is used, so a user
     * mid-line (e.g. {@code "set ke"}) still completes {@code SET}. Results are sorted by name and
     * unmodifiable.
     */
    public static List<Command> complete(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return CATALOG;
        }
        String token = firstToken(prefix).toUpperCase(Locale.ROOT);
        List<Command> out = new ArrayList<>();
        for (Command cmd : CATALOG) {
            if (cmd.name().startsWith(token)) {
                out.add(cmd);
            }
        }
        return List.copyOf(out);
    }

    /** Exact case-insensitive lookup by command name. */
    public static Optional<Command> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (Command cmd : CATALOG) {
            if (cmd.name().equals(upper)) {
                return Optional.of(cmd);
            }
        }
        return Optional.empty();
    }

    /** All commands in {@code group} (case-insensitive), sorted by name, unmodifiable. */
    public static List<Command> inGroup(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        String g = group.trim().toLowerCase(Locale.ROOT);
        List<Command> out = new ArrayList<>();
        for (Command cmd : CATALOG) {
            if (cmd.group().equals(g)) {
                out.add(cmd);
            }
        }
        return List.copyOf(out);
    }

    private static String firstToken(String s) {
        String trimmed = s.strip();
        int sp = 0;
        while (sp < trimmed.length() && !Character.isWhitespace(trimmed.charAt(sp))) {
            sp++;
        }
        return trimmed.substring(0, sp);
    }
}
