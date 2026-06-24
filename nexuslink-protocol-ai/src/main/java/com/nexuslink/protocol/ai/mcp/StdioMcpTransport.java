package com.nexuslink.protocol.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.nexuslink.protocol.ai.mcp.JsonRpc.MAPPER;

/**
 * MCP stdio transport: launches an MCP server as a subprocess and exchanges newline-delimited
 * JSON-RPC messages over its stdin/stdout (the canonical local-server transport, e.g.
 * {@code npx -y @modelcontextprotocol/server-everything}).
 */
public final class StdioMcpTransport implements McpTransport {

    private final List<String> command;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    public StdioMcpTransport(List<String> command) {
        this.command = List.copyOf(command);
    }

    @Override
    public void open() {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // keep stderr separate from the JSON-RPC stream
            this.process = pb.start();
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new McpException("Failed to launch MCP server: " + String.join(" ", command), e);
        }
    }

    @Override
    public synchronized JsonNode sendRequest(ObjectNode request) {
        try {
            writeLine(request.toString());
            // Read lines until we get a JSON object with a matching id (skip server-initiated notifications).
            long wantId = request.path("id").asLong();
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = MAPPER.readTree(line);
                if (node.has("id") && node.path("id").asLong() == wantId) return node;
                // else: a notification or unrelated message — keep reading
            }
            throw new McpException("MCP server closed the stream before responding");
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("stdio transport error: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void sendNotification(ObjectNode notification) {
        writeLine(notification.toString());
    }

    private void writeLine(String json) {
        try {
            stdin.write(json);
            stdin.write('\n');
            stdin.flush();
        } catch (Exception e) {
            throw new McpException("Failed writing to MCP server stdin: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try { if (stdin != null) stdin.close(); } catch (Exception ignored) {}
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
