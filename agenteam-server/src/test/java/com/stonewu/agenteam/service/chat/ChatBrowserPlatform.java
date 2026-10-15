package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** 界面验收按请求路径返回合成平台数据，允许校验、令牌刷新和失败后的重复操作。 */
final class ChatBrowserPlatform implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatBrowserPlatform.class);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, String> applications = new ConcurrentHashMap<>();

    ChatBrowserPlatform() throws IOException {
        JsonNode samples;
        try (var input = new ClassPathResource("contracts/integration/platform-responses.json").getInputStream()) {
            samples = json.readTree(input);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try (exchange) {
                Object response;
                int status = 200;
                if (path.equals("/open-apis/auth/v3/tenant_access_token/internal")) {
                    var input = json.readTree(exchange.getRequestBody());
                    if (input.path("app_secret").asText().contains("invalid")) {
                        response = samples.path("feishu.expiredToken");
                        status = 401;
                    } else {
                        String application = input.path("app_id").asText();
                        String token = "browser-token-" + UUID.nameUUIDFromBytes(application.getBytes(StandardCharsets.UTF_8));
                        applications.put(token, application);
                        response = Map.of("code", 0, "tenant_access_token", token, "expire", 7200);
                    }
                } else if (path.equals("/open-apis/tenant/v2/tenant/query")) {
                    response = samples.path("feishu.tenant");
                } else if (path.startsWith("/open-apis/bot/v3/info")) {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    String token = authorization == null ? "" : authorization.replace("Bearer ", "");
                    String application = applications.getOrDefault(token, "browser-unknown");
                    response = Map.of("code", 0, "bot", Map.of("open_id", "ou_" + UUID.nameUUIDFromBytes(application.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""),
                        "app_name", "界面验收机器人"));
                } else if (path.equals("/open-apis/im/v1/chats")) {
                    response = Map.of("code", 0, "data", Map.of("items", List.of(), "has_more", false));
                } else {
                    status = 400;
                    response = Map.of("code", 999999, "msg", "本次界面验收未配置此模拟接口。");
                }
                byte[] bytes = json.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
                LOG.error("界面验收平台处理失败，路径={}", path, failure);
            }
        });
        server.start();
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
