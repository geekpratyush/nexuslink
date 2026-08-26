package com.nexuslink.protocol.redis;

import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.output.CommandOutput;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Collects <em>any</em> Redis reply into plain Java objects — the output the console needs to send a
 * command the client has never heard of and still print what came back.
 *
 * <p>Lettuce's built-in outputs are shaped for one reply type each ({@code StatusOutput} for simple
 * strings, {@code IntegerOutput} for integers, {@code NestedMultiOutput} for arrays), and its
 * general-purpose {@code ObjectOutput} throws {@link UnsupportedOperationException} on a top-level
 * scalar in RESP2 — which is most replies. This output accepts them all: simple and bulk strings and
 * nulls become {@link String}, integers {@link Long}, doubles {@link Double}, booleans
 * {@link Boolean}, and arrays, sets, pushes and maps become nested {@link List}s.
 */
public final class RedisReplyOutput extends CommandOutput<String, String, Object> {

    /** Open aggregate replies, innermost first; empty while a top-level scalar is being read. */
    private final Deque<List<Object>> stack = new ArrayDeque<>();

    /** How many aggregates are open. The decoder reports a level as finished by calling
     * {@link #complete(int)} with a depth below this one. */
    private int depth;

    public RedisReplyOutput(RedisCodec<String, String> codec) {
        super(codec, null);
    }

    @Override
    public void set(ByteBuffer bytes) {
        store(bytes == null ? null : codec.decodeValue(bytes));
    }

    /** RESP simple strings ({@code +OK}) arrive here rather than through {@link #set(ByteBuffer)}. */
    @Override
    public void setSingle(ByteBuffer bytes) {
        set(bytes);
    }

    @Override
    public void setBigNumber(ByteBuffer bytes) {
        set(bytes);
    }

    @Override public void set(long integer) { store(integer); }

    @Override public void set(double number) { store(number); }

    @Override public void set(boolean value) { store(value); }

    @Override
    public void multi(int count) {
        if (count < 0) { store(null); return; }   // a null array
        List<Object> list = new ArrayList<>(count);
        store(list);
        stack.push(list);
        depth++;
    }

    /** A RESP3 map arrives as {@code count} pairs; flattened to key, value, key, value… */
    @Override
    public void multiMap(int count) {
        multi(count < 0 ? count : count * 2);
    }

    @Override public void multiArray(int count) { multi(count); }

    @Override public void multiSet(int count) { multi(count); }

    @Override public void multiPush(int count) { multi(count); }

    @Override
    public void complete(int level) {
        if (level > 0 && level < depth) {
            stack.pop();
            depth--;
        }
    }

    /** Puts a value where it belongs: into the open aggregate, or into the result when at the top. */
    private void store(Object value) {
        List<Object> parent = stack.peek();
        if (parent == null) output = value;
        else parent.add(value);
    }
}
