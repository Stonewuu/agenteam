package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientSession;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * 使用框架会话关联请求；工具结构按完整 JSON 对象读取，保留所有验证约束。
 */
public final class RemoteMcpSession implements AutoCloseable {
    private static final TypeRef<JsonNode> JSON = new TypeRef<>() {
    };
    private final RestrictedMcpTransport transport;
    private final McpClientSession session;
    private final long deadline;

    public RemoteMcpSession(RestrictedHttpClient http, ObjectMapper json, String endpoint, String transportType,
                            Map<String, String> credentials, Duration timeout, long responseBytes) {
        this(http, json, endpoint, transportType, credentials, timeout, responseBytes, null);
    }

    public RemoteMcpSession(RestrictedHttpClient http, ObjectMapper json, String endpoint, String transportType,
                            Map<String, String> credentials, Duration timeout, long responseBytes,
                            ToolCallControl control) {
        deadline = System.nanoTime() + timeout.toNanos();
        transport = new RestrictedMcpTransport(http, json, endpoint, transportType, credentials, timeout,
            responseBytes);
        session = new McpClientSession(timeout, transport, Map.of(), Map.of(), Function.identity());
        if (control != null) {
            control.track(this);
        }
        try {
            JsonNode initialized = request("initialize",
                Map.of("protocolVersion", transport.protocolVersions().getLast(), "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "AgenTeam", "version", "1.0")));
            if (!transport.protocolVersions().contains(initialized.path("protocolVersion").asText())
                || !initialized.path("capabilities").path("tools").isObject()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CAPABILITY_UNSUPPORTED",
                    Messages.text("error.plugin.remoteMcpSession.init.1"));
            }
            session.sendNotification("notifications/initialized", Map.of()).block(remaining());
        } catch (RuntimeException failure) {
            close();
            throw safeFailure(failure);
        }
    }

    public List<JsonNode> listTools() {
        List<JsonNode> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            JsonNode page = request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
            if (!page.path("tools").isArray()) {
                throw invalidTools();
            }
            page.path("tools").forEach(tools::add);
            if (tools.size() > 100) {
                throw invalidTools();
            }
            cursor = page.path("nextCursor").isMissingNode() || page.path("nextCursor").isNull() ? null : page.path(
                "nextCursor").asText();
            if (cursor != null && (cursor.length() > 8192 || !cursors.add(cursor) || cursors.size() > 100)) {
                throw invalidTools();
            }
        } while (cursor != null && !cursor.isEmpty());
        return List.copyOf(tools);
    }

    public JsonNode call(String name, JsonNode arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments));
    }

    public JsonNode call(String name, JsonNode arguments, Runnable beforeSend) {
        transport.beforeToolSend(beforeSend);
        return call(name, arguments);
    }

    private JsonNode request(String method, Object params) {
        try {
            JsonNode result = session.sendRequest(method, params, JSON).block(remaining());
            if (result == null || !result.isObject()) {
                throw invalidTools();
            }
            return result;
        } catch (RuntimeException failure) {
            throw safeFailure(failure);
        }
    }

    private Duration remaining() {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "REMOTE_TIMEOUT", Messages.text("error.plugin.remoteMcpSession.remaining.1"));
        }
        return Duration.ofNanos(nanos);
    }

    @Override
    public void close() {
        transport.cancel();
        session.close();
    }

    private static ApiException invalidTools() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MCP_TOOL_STRUCTURE_INVALID",
            Messages.text("error.plugin.remoteMcpSession.invalidTools.1"));
    }

    private static ApiException safeFailure(RuntimeException failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof ApiException known) {
                return known;
            }
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED",
            Messages.text("error.plugin.remoteMcpSession.safeFailure.1"), failure);
    }
}
