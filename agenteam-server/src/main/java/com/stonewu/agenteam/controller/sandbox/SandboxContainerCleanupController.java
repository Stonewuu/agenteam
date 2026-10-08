package com.stonewu.agenteam.controller.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.workspace.SandboxContainerCleanup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 仅供受信任应用清理指定工作空间；不接收任意命令、容器名或文件路径。
 */
public final class SandboxContainerCleanupController implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxContainerCleanupController.class);

    private static final Pattern TARGET = Pattern.compile("^/container-cleanup/(user|workspace)/([0-9a-f]{64})$");

    private final SandboxContainerCleanup containers;

    private final ObjectMapper json;

    private final byte[] authorization;

    public SandboxContainerCleanupController(SandboxContainerCleanup containers, ObjectMapper json, String token) {
        this.containers = containers;
        this.json = json;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        try {
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, "UNAUTHORIZED");
                return;
            }
            var target = TARGET.matcher(path);
            if (!target.matches() || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, "NOT_FOUND");
                return;
            }
            if (!exchange.getRequestMethod().equals("DELETE")) {
                reply(exchange, 405, "METHOD_NOT_ALLOWED");
                return;
            }
            if (target.group(1).equals("user")) {
                containers.removeUserContainer(target.group(2), Duration.ofSeconds(30));
            } else {
                containers.removeForWorkspace(target.group(2), Duration.ofSeconds(30), null);
            }
            exchange.sendResponseHeaders(204, -1);
        } catch (Exception failure) {
            LOG.error("独立节点清理失败，请求编号 {}，方法 {}，路径 {}", requestId, exchange.getRequestMethod(), path,
                failure);
            reply(exchange, 500, "SANDBOX_CLEANUP_FAILED");
        } finally {
            exchange.close();
        }
    }

    private void reply(HttpExchange exchange, int status, String code) throws IOException {
        byte[] body = json.writeValueAsBytes(Map.of("code", code));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
