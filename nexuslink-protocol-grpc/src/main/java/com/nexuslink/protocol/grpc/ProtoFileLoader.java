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

    /** The parsed proto: syntax ("proto3"/"proto2"), package (may be blank), services and message names. */
    public record ProtoFile(String syntax, String packageName, List<Service> services, List<String> messages) {}

    private static final Pattern SYNTAX = Pattern.compile("syntax\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern MESSAGE = Pattern.compile("\\bmessage\\s+([A-Za-z_]\\w*)");
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
        List<String> messages = allFirstGroups(MESSAGE, src);
        List<Service> services = parseServices(src);
        return new ProtoFile(syntax, pkg, services, messages);
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

    private static List<String> allFirstGroups(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
