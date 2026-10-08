package com.stonewu.agenteam.controller.sandbox;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.sandbox.SandboxExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 此入口只由独立启动器注册，不进入业务接口路由。
 */
public final class SandboxServerController implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxServerController.class);

    private final SandboxExecutionService executions;

    private final byte[] authorization;

    public SandboxServerController(SandboxExecutionService executions, String token) {
        this.executions = executions;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String id = path.startsWith("/executions/") ? path.substring("/executions/".length()) : "";
        boolean committed = false;
        try {
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, "执行服务认证失败");
                return;
            }
            if (!id.matches("[0-9a-f-]{36}") || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, "执行请求不存在");
                return;
            }
            if (exchange.getRequestMethod().equals("DELETE")) {
                executions.cancel(id);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, "请求方法不受支持");
                return;
            }
            try (var result = executions.execute(id, exchange.getRequestBody())) {
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, Files.size(result.path()));
                committed = true;
                try (var input = Files.newInputStream(result.path())) {
                    input.transferTo(exchange.getResponseBody());
                }
            }
        } catch (Exception failure) {
            LOG.error("独立执行请求失败，执行编号 {}，方法 {}，路径 {}", id, exchange.getRequestMethod(), path, failure);
            if (!committed) {
                int status = failure instanceof ApiException known ? known.getStatusCode().value() : 500;
                reply(exchange, status, "执行服务未能完成本次请求");
            }
        } finally {
            exchange.close();
        }
    }

    private void reply(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
