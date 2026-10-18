package com.stonewu.agenteam.controller.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.sandbox.request.EnvironmentTerminalRequest;
import com.stonewu.agenteam.service.environment.UserTerminalRuntime;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ExceptionDiagnostics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 独立沙盒进程的交互终端入口，由 SandboxServerMain 注册。
 * 只处理经过认证且已在共享存储登记的用户环境。
 */
public final class EnvironmentTerminalServerController implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentTerminalServerController.class);

    private final UserTerminalRuntime runtime;

    private final ObjectMapper json;

    private final byte[] authorization;

    public EnvironmentTerminalServerController(UserTerminalRuntime runtime, ObjectMapper json, String token) {
        this.runtime = runtime;
        this.json = json;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String workspace = null;
        try {
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, Map.of("code", "UNAUTHORIZED"));
                return;
            }
            if (!"/terminals".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, Map.of("code", "NOT_FOUND"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, 405, Map.of("code", "METHOD_NOT_ALLOWED"));
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(262145);
            if (bytes.length > 262144) {
                reply(exchange, 413, Map.of("code", "REQUEST_TOO_LARGE"));
                return;
            }
            var request = json.readValue(bytes, EnvironmentTerminalRequest.class);
            if (request.project() == null) {
                throw ApiException.invalidField("project", "环境信息不完整。");
            }
            workspace = request.project().workspaceId();
            reply(exchange, 200, runtime.exchange(request.project(), request.input()));
        } catch (Exception failure) {
            Throwable cause = failure instanceof JsonProcessingException ? ExceptionDiagnostics.withoutMessages(failure) : failure;
            LOG.warn("独立终端失败，工作空间编号 {}，方法 {}，路径 /terminals", workspace, exchange.getRequestMethod(), cause);
            int status = failure instanceof ApiException known ? known.getStatusCode().value() : 500;
            reply(exchange, status, Map.of("code", failure instanceof ApiException known ? known.code() : "ENVIRONMENT_UNAVAILABLE"));
        } finally {
            exchange.close();
        }
    }

    private void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
