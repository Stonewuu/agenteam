package com.stonewu.agenteam.controller.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectCommandExecutor;
import com.stonewu.agenteam.service.sandbox.SandboxExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 只接受受认证的项目引用，执行服务读取共享存储上的登记文件后再挂载。
 */
public final class ProjectSandboxServerController implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectSandboxServerController.class);

    private final SandboxExecutionService executions;

    private final ProjectCommandExecutor projects;

    private final ObjectMapper json;

    private final byte[] authorization;

    public ProjectSandboxServerController(SandboxExecutionService executions, ProjectCommandExecutor projects,
                                          ObjectMapper json, String token) {
        this.executions = executions;
        this.projects = projects;
        this.json = json;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String id = path.startsWith("/project-executions/") ? path.substring("/project-executions/".length()) : "";
        try {
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, Map.of("code", "UNAUTHORIZED"));
                return;
            }
            if (!id.matches("[0-9a-f-]{36}") || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, Map.of("code", "NOT_FOUND"));
                return;
            }
            if (exchange.getRequestMethod().equals("DELETE")) {
                executions.cancel(id);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, Map.of("code", "METHOD_NOT_ALLOWED"));
                return;
            }
            var result = executions.executeProject(id, exchange.getRequestBody(), projects);
            reply(exchange, 200, result);
        } catch (Exception failure) {
            LOG.error("独立项目执行失败，执行编号 {}，方法 {}，路径 {}", id, exchange.getRequestMethod(), path, failure);
            int status = failure instanceof ApiException known ? known.getStatusCode().value() : 500;
            String code = failure instanceof ApiException known ? known.code() : "SANDBOX_FAILED";
            reply(exchange, status, Map.of("code", code));
        } finally {
            exchange.close();
        }
    }

    private void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
