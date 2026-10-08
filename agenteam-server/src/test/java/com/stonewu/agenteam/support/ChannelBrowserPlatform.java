package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 仅用于隔离界面验收。应用接口直接复用官方协议样本，授权页明确显示为本机模拟。
 */
public final class ChannelBrowserPlatform implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json = new ObjectMapper();
    private final JsonNode fixtures;
    private final Map<String, Authorization> requests = new ConcurrentHashMap<>();
    private final Map<String, Authorization> codes = new ConcurrentHashMap<>();
    private final Map<String, SentMessage> messages = new ConcurrentHashMap<>();
    private final String frontend;

    public ChannelBrowserPlatform(String frontend) throws IOException {
        this.frontend = frontend;
        try (var input = new ClassPathResource("contracts/integration/platform-responses.json").getInputStream()) {
            fixtures = json.readTree(input);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            var query = parameters(exchange.getRequestURI().getRawQuery());
            if (path.equals("/open-apis/authen/v1/authorize") && exchange.getRequestMethod().equals("GET")) {
                authorize(exchange, query);
            } else if (path.equals("/approve") && exchange.getRequestMethod().equals("GET")) {
                approve(exchange, query);
            } else if (path.equals("/oauth/v3/token") && exchange.getRequestMethod().equals("POST")) {
                token(exchange);
            } else if (path.equals("/open-apis/auth/v3/tenant_access_token/internal") && exchange.getRequestMethod().equals("POST")) {
                var body = json.readTree(exchange.getRequestBody());
                if (!"cli_browser_test".equals(body.path("app_id").asText()) || !"test-ui-secret".equals(body.path("app_secret").asText())) {
                    fixture(exchange, 401, "feishu.expiredToken");
                    return;
                }
                fixture(exchange, 200, "feishu.token");
            } else if (path.equals("/open-apis/tenant/v2/tenant/query") && exchange.getRequestMethod().equals("GET")
                && bearer(exchange, "test-feishu-application-token")) {
                fixture(exchange, 200, "feishu.tenant");
            } else if (path.equals("/open-apis/authen/v1/user_info") && exchange.getRequestMethod().equals("GET")
                && bearer(exchange, "test-feishu-user-token")) {
                fixture(exchange, 200, "feishu.identity");
            } else if (path.equals("/open-apis/im/v1/messages") && exchange.getRequestMethod().equals("POST")
                && bearer(exchange, "test-feishu-application-token") && "open_id".equals(query.get("receive_id_type"))) {
                message(exchange);
            } else {
                write(exchange, 404, "application/json", "{\"code\":404,\"msg\":\"模拟平台不支持此请求\"}");
            }
        }
    }

    private void message(HttpExchange exchange) throws IOException {
        var body = json.readTree(exchange.getRequestBody());
        if (!"ou_test_member".equals(body.path("receive_id").asText())) {
            fixture(exchange, 400, "feishu.unavailableRecipient");
            return;
        }
        if (!"text".equals(body.path("msg_type").asText()) || !body.path("content").isTextual()
            || body.path("uuid").asText().isBlank() || body.path("uuid").asText().length() > 50
            || !json.readTree(body.path("content").asText()).path("text").isTextual()) {
            write(exchange, 400, "application/json", "{\"code\":230001,\"msg\":\"Your request contains an invalid request parameter.\"}");
            return;
        }
        Instant now = Instant.now();
        var sent = messages.compute(body.path("uuid").asText(), (key, previous) -> {
            if (previous != null && previous.expires().isAfter(now)) {
                return previous;
            }
            ObjectNode response = fixtures.get("feishu.accepted").deepCopy();
            ObjectNode data = (ObjectNode) response.path("data");
            data.put("message_id", "om_mock_" + UUID.randomUUID().toString().replace("-", ""));
            data.put("create_time", Long.toString(now.toEpochMilli()));
            data.put("update_time", Long.toString(now.toEpochMilli()));
            ((ObjectNode) data.path("body")).put("content", body.path("content").asText());
            return new SentMessage(response.toString(), now.plusSeconds(3600));
        });
        write(exchange, 200, "application/json", sent.response());
    }

    private void authorize(HttpExchange exchange, Map<String, String> input) throws IOException {
        String redirect = input.getOrDefault("redirect_uri", "");
        if (!redirect.startsWith(frontend + "/api/v1/auth/channel-callbacks/") || !"code".equals(input.get("response_type"))
            || !"cli_browser_test".equals(input.get("client_id")) || !"S256".equals(input.get("code_challenge_method"))
            || !input.getOrDefault("code_challenge", "").matches("[A-Za-z0-9_-]{43}")
            || !input.getOrDefault("state", "").matches("[A-Za-z0-9_-]{43}")) {
            write(exchange, 400, "text/plain", "模拟授权参数不符合文档约定");
            return;
        }
        String id = UUID.randomUUID().toString();
        requests.put(id, new Authorization(redirect, input.get("client_id"), input.get("state"), input.get("code_challenge"), Instant.now().plusSeconds(300)));
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>飞书模拟授权</title><main style=\"max-width:480px;margin:10vh auto;padding:24px;font-family:system-ui\">"
            + "<h1>本机飞书模拟授权</h1><p>测试成员：测试成员</p><p>此页面只用于隔离验收，不连接真实飞书账号。</p>"
            + "<a href=\"/approve?request=" + HtmlUtils.htmlEscape(id) + "\">同意授权</a></main></html>";
        write(exchange, 200, "text/html", html);
    }

    private void approve(HttpExchange exchange, Map<String, String> input) throws IOException {
        var authorization = requests.remove(input.getOrDefault("request", ""));
        if (authorization == null || authorization.expiresAt().isBefore(Instant.now())) {
            write(exchange, 400, "text/plain", "模拟授权已过期");
            return;
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, authorization);
        exchange.getResponseHeaders().set("Location", authorization.redirect() + "?code=" + code + "&state="
            + URLEncoder.encode(authorization.state(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(303, -1);
    }

    private void token(HttpExchange exchange) throws IOException {
        var form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var authorization = codes.remove(form.getOrDefault("code", ""));
        boolean valid = authorization != null && authorization.expiresAt().isAfter(Instant.now())
            && authorization.client().equals(form.get("client_id")) && authorization.redirect().equals(form.get("redirect_uri"))
            && "authorization_code".equals(form.get("grant_type")) && "test-ui-secret".equals(form.get("client_secret"));
        if (valid) {
            try {
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(form.getOrDefault("code_verifier", "").getBytes(StandardCharsets.US_ASCII)));
                valid = authorization.challenge().equals(challenge);
            } catch (Exception failure) {
                throw new IOException("模拟平台无法验证授权材料", failure);
            }
        }
        fixture(exchange, valid ? 200 : 400, valid ? "feishu.userToken" : "feishu.expiredCode");
    }

    private boolean bearer(HttpExchange exchange, String token) {
        return ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private void fixture(HttpExchange exchange, int status, String name) throws IOException {
        if (!fixtures.has(name)) {
            throw new IOException("没有找到模拟协议样本：" + name);
        }
        write(exchange, status, "application/json", fixtures.get(name).toString());
    }

    private void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private Map<String, String> parameters(String value) {
        var result = new LinkedHashMap<String, String>();
        if (value != null && !value.isEmpty()) {
            for (String pair : value.split("&")) {
                var parts = pair.split("=", 2);
                result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record Authorization(String redirect, String client, String state, String challenge, Instant expiresAt) {
    }

    private record SentMessage(String response, Instant expires) {
    }
}
