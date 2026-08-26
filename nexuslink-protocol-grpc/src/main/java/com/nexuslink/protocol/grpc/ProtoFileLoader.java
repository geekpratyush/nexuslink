package com.nexuslink.protocol.grpc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dependency-free parser for a {@code .proto} file — the offline alternative to server reflection
 * (§6.1). It extracts what the method picker needs: the syntax + package, the declared message type
 * names, and each service's RPCs with their input/output types and client/server streaming flags. It
 * is a structural parser (not a full protobuf compiler): comments are stripped, then services are
 * brace-matched and their {@code rpc} lines scanned. Pure and JavaFX-free, so it is fully unit-testable.
 */
public final class ProtoFileLoader {

    /** One RPC method: its name, input/output message types and streaming flags. */
    public record Method(String name, String inputType, String outputType,
                         boolean clientStreaming, boolean serverStreaming) {
        public boolean isUnary() { return !clientStreaming && !serverStreaming; }
    }

    /** A service and its methods, in declaration order. */
    public record Service(String name, List<Method> methods) {}

    /** One field of a message: its declared type, name and label. */
    public record Field(String name, String type, boolean repeated, boolean map) {}

    /** A message type and its fields, in declaration order (nested types are registered separately). */
    public record MessageType(String name, List<Field> fields) {}

    /** An enum type and its value names, in declaration order. */
    public record EnumType(String name, List<String> values) {}

    /**
     * The parsed proto: syntax ("proto3"/"proto2"), package (may be blank), services, message types
     * and enum types. Nested messages/enums are hoisted into the flat lists under their simple name.
     */
    public record ProtoFile(String syntax, String packageName, List<Service> services,
                            List<MessageType> messageTypes, List<EnumType> enums) {

        /** The declared message names, in declaration order. */
        public List<String> messages() { return messageTypes.stream().map(MessageType::name).toList(); }

        /** The message declared as {@code name} (simple or fully-qualified), or null. */
        public MessageType message(String name) { return find(messageTypes, MessageType::name, name); }

        /** The enum declared as {@code name} (simple or fully-qualified), or null. */
        public EnumType enumType(String name) { return find(enums, EnumType::name, name); }

        /**
         * A JSON request skeleton for {@code typeName} — every field at its proto3 default, nested
         * messages expanded (recursion is cut off at a repeated type), unknown types as {@code {}}.
         */
        public String requestTemplate(String typeName) {
            StringBuilder out = new StringBuilder();
            appendMessage(out, message(typeName), 0, new java.util.ArrayDeque<>());
            return out.toString();
        }

        private void appendMessage(StringBuilder out, MessageType type, int indent, java.util.Deque<String> open) {
            if (type == null || type.fields().isEmpty() || open.contains(type.name())) { out.append("{}"); return; }
            open.push(type.name());
            String pad = "  ".repeat(indent + 1);
            out.append("{\n");
            for (int i = 0; i < type.fields().size(); i++) {
                Field f = type.fields().get(i);
                out.append(pad).append('"').append(f.name()).append("\": ");
                appendValue(out, f, indent + 1, open);
                if (i < type.fields().size() - 1) out.append(',');
                out.append('\n');
            }
            out.append("  ".repeat(indent)).append('}');
            open.pop();
        }

        private void appendValue(StringBuilder out, Field f, int indent, java.util.Deque<String> open) {
            if (f.map()) { out.append("{}"); return; }
            if (f.repeated()) { out.append("[]"); return; }
            String scalar = scalarDefault(f.type());
            if (scalar != null) { out.append(scalar); return; }
            EnumType e = enumType(f.type());
            if (e != null) { out.append('"').append(e.values().isEmpty() ? "" : e.values().get(0)).append('"'); return; }
            appendMessage(out, message(f.type()), indent, open);
        }

        private static <T> T find(List<T> items, java.util.function.Function<T, String> name, String wanted) {
            if (wanted == null || wanted.isBlank()) return null;
            String simple = wanted.substring(wanted.lastIndexOf('.') + 1);
            for (T t : items) if (name.apply(t).equals(wanted)) return t;
            for (T t : items) if (name.apply(t).equals(simple)) return t;
            return null;
        }
    }

    /** The proto3 JSON default for a scalar type, or null when the type is not a scalar. */
    private static String scalarDefault(String type) {
        return switch (type) {
            case "string" -> "\"\"";
            case "bytes" -> "\"\"";
            case "bool" -> "false";
            case "double", "float", "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "0";
            // proto3 JSON carries 64-bit integers as strings, so the skeleton uses one too.
            case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> "\"0\"";
            default -> null;
        };
    }

    private static final Pattern SYNTAX = Pattern.compile("syntax\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern MESSAGE = Pattern.compile("\\bmessage\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Pattern ENUM = Pattern.compile("\\benum\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Pattern NESTED = Pattern.compile("\\b(message|enum|oneof)\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Pattern FIELD = Pattern.compile(
            "(?:(repeated|optional|required)\\s+)?(map\\s*<\\s*[\\w.]+\\s*,\\s*\\.?[\\w.]+\\s*>|\\.?[A-Za-z_][\\w.]*)"
                    + "\\s+([A-Za-z_]\\w*)\\s*=\\s*\\d+");
    private static final Pattern ENUM_VALUE = Pattern.compile("([A-Za-z_]\\w*)\\s*=\\s*-?\\d+");
    /** Statement keywords that must never be mistaken for a field type. */
    private static final java.util.Set<String> NOT_A_TYPE =
            java.util.Set.of("option", "reserved", "extend", "extensions", "rpc", "returns", "syntax", "import");
    private static final Pattern SERVICE = Pattern.compile("\\bservice\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Pattern RPC = Pattern.compile(
            "\\brpc\\s+([A-Za-z_]\\w*)\\s*\\(\\s*(stream\\s+)?\\.?([\\w.]+)\\s*\\)"
                    + "\\s*returns\\s*\\(\\s*(stream\\s+)?\\.?([\\w.]+)\\s*\\)");

    private ProtoFileLoader() {}

    /** Parses {@code protoText}; a null/blank input yields an empty proto (proto2 default, no services). */
    public static ProtoFile parse(String protoText) {
        String src = stripComments(protoText == null ? "" : protoText);
        String syntax = firstGroup(SYNTAX, src, "proto2");   // absent → proto2 per the spec
        String pkg = firstGroup(PACKAGE, src, "");
        List<Service> services = parseServices(src);
        List<MessageType> messages = new ArrayList<>();
        List<EnumType> enums = new ArrayList<>();
        collectTypes(stripServices(src), messages, enums);
        return new ProtoFile(syntax, pkg, services, List.copyOf(messages), List.copyOf(enums));
    }

    /**
     * Collects every {@code message} and {@code enum} declared in {@code src}, recursing into nested
     * declarations so they are hoisted into the flat lists under their simple name.
     */
    private static void collectTypes(String src, List<MessageType> messages, List<EnumType> enums) {
        Matcher m = MESSAGE.matcher(src);
        while (m.find()) {
            int open = m.end() - 1;
            int close = matchBrace(src, open);
            if (close < 0) break;
            String body = src.substring(open + 1, close);
            messages.add(new MessageType(m.group(1), parseFields(body)));
            collectTypes(body, messages, enums);              // nested messages/enums
            m.region(close, src.length());
        }
        Matcher e = ENUM.matcher(src);
        while (e.find()) {
            int open = e.end() - 1;
            int close = matchBrace(src, open);
            if (close < 0) break;
            enums.add(new EnumType(e.group(1), parseEnumValues(src.substring(open + 1, close))));
            e.region(close, src.length());
        }
    }

    /** The fields declared directly in a message body — nested types removed, {@code oneof} flattened. */
    private static List<Field> parseFields(String messageBody) {
        String flat = flatten(messageBody);
        List<Field> fields = new ArrayList<>();
        Matcher f = FIELD.matcher(flat);
        while (f.find()) {
            String type = f.group(2).replaceAll("\\s+", "");
            if (NOT_A_TYPE.contains(type)) continue;
            boolean map = type.startsWith("map<");
            if (!map && type.startsWith(".")) type = type.substring(1);
            fields.add(new Field(f.group(3), type, "repeated".equals(f.group(1)), map));
        }
        return fields;
    }

    private static List<String> parseEnumValues(String enumBody) {
        List<String> values = new ArrayList<>();
        Matcher v = ENUM_VALUE.matcher(flatten(enumBody));
        while (v.find()) values.add(v.group(1));
        return values;
    }

    /** Drops nested {@code message}/{@code enum} blocks and splices {@code oneof} bodies inline. */
    private static String flatten(String body) {
        StringBuilder out = new StringBuilder(body);
        Matcher n = NESTED.matcher(out.toString());
        while (n.find()) {
            int open = n.end() - 1;
            int close = matchBrace(out.toString(), open);
            if (close < 0) break;
            String replacement = "oneof".equals(n.group(1)) ? out.substring(open + 1, close) : "";
            out.replace(n.start(), close + 1, replacement);
            n = NESTED.matcher(out.toString());               // offsets moved — rescan
        }
        return out.toString();
    }

    /** Blanks out service bodies so their {@code rpc} lines cannot look like fields. */
    private static String stripServices(String src) {
        StringBuilder out = new StringBuilder(src);
        Matcher s = SERVICE.matcher(out.toString());
        while (s.find()) {
            int open = s.end() - 1;
            int close = matchBrace(out.toString(), open);
            if (close < 0) break;
            out.replace(s.start(), close + 1, "");
            s = SERVICE.matcher(out.toString());
        }
        return out.toString();
    }

    private static List<Service> parseServices(String src) {
        List<Service> services = new ArrayList<>();
        Matcher m = SERVICE.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            int bodyStart = m.end() - 1;                 // index of the opening '{'
            int bodyEnd = matchBrace(src, bodyStart);
            if (bodyEnd < 0) break;                       // unbalanced — stop rather than loop
            String body = src.substring(bodyStart + 1, bodyEnd);
            services.add(new Service(name, parseMethods(body)));
            m.region(bodyEnd, src.length());
        }
        return services;
    }

    private static List<Method> parseMethods(String serviceBody) {
        List<Method> methods = new ArrayList<>();
        Matcher r = RPC.matcher(serviceBody);
        while (r.find()) {
            methods.add(new Method(r.group(1), r.group(3), r.group(5),
                    r.group(2) != null, r.group(4) != null));
        }
        return methods;
    }

    /** Returns the index of the '}' matching the '{' at {@code open}, or -1 if unbalanced. */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** Removes {@code /* *}{@code /} block comments and {@code //} line comments. */
    private static String stripComments(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("//[^\\n]*", "");
    }

    private static String firstGroup(Pattern p, String s, String fallback) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : fallback;
    }
}
