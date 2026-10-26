package com.stonewu.agenteam.service.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** 沿用官方注册协议，将轮询拆成可持久恢复的步骤，网络诊断复用渠道客户端。 */
@Component
public class FeishuAppClient {
    public static final String MANAGE_SCOPE = "application:application:patch";
    public static final List<String> MANAGEMENT_SCOPES = List.of(MANAGE_SCOPE, "application:application:self_manage", "tenant:tenant:readonly");
    public static final List<String> ENTERPRISE_SCOPES = List.of(MANAGE_SCOPE, "application:application:self_manage", "tenant:tenant:readonly", "im:message:send_as_bot");
    public static final List<String> BOT_SCOPES = List.of("application:application:self_manage", "tenant:tenant:readonly",
        "im:message:send_as_bot", "im:message.p2p_msg:readonly", "im:message.group_at_msg:readonly", "im:chat:read", "im:resource");
    public static final List<String> EVENTS = List.of("im.message.receive_v1", "im.chat.member.bot.added_v1", "im.chat.member.bot.deleted_v1");
    private final IntegrationHttpClient http;
    private final IntegrationEndpoints endpoints;
    private final ChannelResponseMapper responses;
    private final ObjectMapper json;

    public FeishuAppClient(IntegrationHttpClient http, IntegrationEndpoints endpoints, ChannelResponseMapper responses, ObjectMapper json) {
        this.http = http;
        this.endpoints = endpoints;
        this.responses = responses;
        this.json = json;
    }

    public record Started(FeishuSetupCipher.Registration material, int expiresIn, int interval) {
    }
    public record Polled(String state, String appId, String secret) {
        @Override
        public String toString() {
            return "Polled[state=" + state + ", 凭据已隐藏]";
        }
    }

    public Started begin(String job, String kind, String name, String description) {
        var response = http.postForm(job, endpoints.feishuAccounts("/oauth/v1/app/registration"),
            Map.of("action", "begin", "archetype", "PersonalAgent", "auth_method", "client_secret", "request_user_info", "open_id"), Set.of());
        var body = registrationBody(response);
        String device = required(body, "device_code");
        var link = HttpUrl.get(allowedLink(required(body, "verification_uri_complete"))).newBuilder()
            .setQueryParameter("from", "sdk").setQueryParameter("source", "agenteam").setQueryParameter("tp", "sdk")
            .setQueryParameter("name", name).setQueryParameter("desc", description == null ? "" : description)
            .setQueryParameter("createOnly", "true").setQueryParameter("addons", addons(kind)).build().uri();
        int expiry = body.path("expires_in").asInt(body.path("expire_in").asInt(600));
        int interval = Math.max(1, Math.min(120, body.path("interval").asInt(5)));
        return new Started(new FeishuSetupCipher.Registration(device, link.toString(), null, null), Math.max(1, Math.min(1800, expiry)), interval);
    }

    public Polled poll(String job, String device) {
        var response = http.postForm(job, endpoints.feishuAccounts("/oauth/v1/app/registration"),
            Map.of("action", "poll", "device_code", device), Set.of("authorization_pending", "slow_down", "expired_token", "access_denied"));
        var body = response.body();
        if (body == null || !body.isObject()) {
            throw failure("FEISHU_REGISTRATION_UNCERTAIN", "暂时无法确认飞书创建结果，请稍后继续检查。");
        }
        if (response.status() == 200 && body.hasNonNull("client_id") && body.hasNonNull("client_secret")) {
            String appId = required(body, "client_id");
            if (!appId.matches("cli_[A-Za-z0-9]{1,100}")) {
                throw failure("FEISHU_REGISTRATION_INVALID", "飞书返回的应用编号无效。");
            }
            return new Polled("created", appId, required(body, "client_secret"));
        }
        String state = body.path("error").asText();
        if (Set.of("authorization_pending", "slow_down", "expired_token", "access_denied").contains(state)) {
            return new Polled(state, null, null);
        }
        if ("lark".equals(body.path("user_info").path("tenant_brand").asText())) {
            throw failure("FEISHU_ACCOUNT_REQUIRED", "请使用飞书企业账号完成创建。");
        }
        throw failure("FEISHU_REGISTRATION_UNCERTAIN", "飞书尚未返回明确创建结果，请检查创建页面后重试查询。");
    }

    public String token(String record, String appId, String secret) {
        return responses.token(http.post(record, endpoints.feishu("/open-apis/auth/v3/tenant_access_token/internal"),
            Map.of(), json.createObjectNode().put("app_id", appId).put("app_secret", secret), null), "tenant_access_token", "expire", true).value();
    }

    public JsonNode get(String record, String path, Map<String, String> query, String token) {
        return success(http.get(record, endpoints.feishu(path), query, token));
    }

    public JsonNode post(String record, String path, JsonNode body, String token) {
        return success(http.post(record, endpoints.feishu(path), Map.of(), body, token));
    }

    public void patch(String record, String path, JsonNode body, String token) {
        success(http.patch(record, endpoints.feishu(path), body, token));
    }

    public JsonNode app(String record, String appId, String token) {
        return get(record, "/open-apis/application/v6/applications/" + appId, Map.of("lang", "zh_cn"), token).path("data").path("app");
    }

    public JsonNode version(String record, String appId, String version, String token) {
        return get(record, "/open-apis/application/v6/applications/" + appId + "/app_versions/" + version,
            Map.of("lang", "zh_cn"), token).path("data").path("app_version");
    }

    public void requireScopes(String record, String token, String kind) {
        var granted = grantedScopes(record, token);
        boolean ready = tenantScopes(kind).stream().allMatch(scope -> granted.contains(scope + ":tenant"));
        if (!kind.equals("bot")) {
            ready &= granted.contains(MANAGE_SCOPE + ":user") && granted.contains("offline_access:user");
        }
        if (!ready) {
            throw failure("FEISHU_SCOPES_REQUIRED", "飞书尚未授予创建流程所需的全部权限，请完成平台审批后继续。");
        }
    }

    public void requireSelfManagement(String record, String token) {
        if (!grantedScopes(record, token).contains("application:application:self_manage:tenant")) {
            throw failure("FEISHU_SELF_MANAGEMENT_REQUIRED", "此应用尚未开通自动配置权限，请在飞书为此应用开通“管理应用自身”并发布后重试，或新建企业接入。");
        }
    }

    private Set<String> grantedScopes(String record, String token) {
        var granted = new HashSet<String>();
        var data = get(record, "/open-apis/application/v6/scopes", Map.of(), token).path("data").path("scopes");
        for (var scope : data) {
            if (scope.path("grant_status").asInt() == 1) {
                granted.add(scope.path("scope_name").asText() + ":" + scope.path("scope_type").asText("tenant"));
            }
        }
        return granted;
    }

    public ObjectNode configuration(String kind, URI callback) {
        return configuration(kind, callback, callback);
    }

    public ObjectNode configuration(String kind, URI callback, URI managementCallback) {
        var body = json.createObjectNode();
        var redirects = body.putObject("security").putObject("add").putArray("redirect_urls").add(callback.toString());
        if (kind.equals("enterprise") && !callback.equals(managementCallback)) {
            redirects.add(managementCallback.toString());
        }
        if (!kind.equals("bot")) {
            ((ObjectNode) body.path("security")).put("allow_refresh_token", true);
        } else {
            var event = body.putObject("event").put("subscription_type", "websocket");
            EVENTS.forEach(event.putArray("add_events")::add);
            body.putObject("callback").put("callback_type", "websocket").putArray("add_callbacks").add("card.action.trigger");
        }
        var scopes = body.putObject("scope").putArray("add_scopes");
        tenantScopes(kind).forEach(scope -> scopes.addObject().put("scope_name", scope).put("token_type", "tenant"));
        if (!kind.equals("bot")) {
            List.of(MANAGE_SCOPE, "offline_access").forEach(scope -> scopes.addObject().put("scope_name", scope).put("token_type", "user"));
        }
        return body;
    }

    public JsonNode exchange(String record, String appId, String secret, String code, URI callback, String verifier) {
        return post(record, "/open-apis/authen/v2/oauth/token", json.createObjectNode().put("grant_type", "authorization_code")
            .put("client_id", appId).put("client_secret", secret).put("code", code).put("redirect_uri", callback.toString())
            .put("code_verifier", verifier), null);
    }

    public JsonNode refresh(String record, String appId, String secret, String refreshToken) {
        return post(record, "/open-apis/authen/v2/oauth/token", json.createObjectNode().put("grant_type", "refresh_token")
            .put("client_id", appId).put("client_secret", secret).put("refresh_token", refreshToken), null);
    }

    public URI authorization(String appId, URI callback, String state, String challenge) {
        return IntegrationHttpClient.withQuery(endpoints.feishuAccounts("/open-apis/authen/v1/authorize"), Map.of(
            "client_id", appId, "response_type", "code", "redirect_uri", callback.toString(), "scope", MANAGE_SCOPE + " offline_access",
            "state", state, "code_challenge", challenge, "code_challenge_method", "S256"));
    }

    public static URI allowedLink(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
            || uri.getPort() != -1 && uri.getPort() != 443 || !Set.of("open.feishu.cn", "accounts.feishu.cn").contains(uri.getHost())) {
            throw failure("FEISHU_LINK_INVALID", "飞书未返回可用的创建地址。");
        }
        return uri;
    }

    private String addons(String kind) {
        var root = json.createObjectNode().put("preset", false);
        var scopes = root.putObject("scopes");
        var tenant = scopes.putArray("tenant");
        tenantScopes(kind).forEach(tenant::add);
        if (!kind.equals("bot")) {
            scopes.putArray("user").add(MANAGE_SCOPE).add("offline_access");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) {
                gzip.write(json.writeValueAsBytes(root));
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("飞书创建参数无法生成", failure);
        }
    }

    private List<String> tenantScopes(String kind) {
        return switch (kind) {
            case "management" -> MANAGEMENT_SCOPES;
            case "enterprise" -> ENTERPRISE_SCOPES;
            default -> BOT_SCOPES;
        };
    }

    private JsonNode registrationBody(ChannelHttpResponse response) {
        if (response.status() != 200 || response.body() == null || !response.body().isObject() || response.body().hasNonNull("error")) {
            throw failure("FEISHU_REGISTRATION_UNAVAILABLE", "飞书暂时无法开始创建，请稍后重试。");
        }
        return response.body();
    }

    private JsonNode success(ChannelHttpResponse response) {
        String code = response.body() == null ? "" : response.body().path("code").asText();
        if (Set.of("99991663", "99991665", "20005", "20008", "20037").contains(code)) {
            throw failure("FEISHU_AUTHORIZATION_REQUIRED", "飞书管理授权已失效，请重新授权后继续。");
        }
        if (Set.of("210036", "210037").contains(code)) {
            throw failure("FEISHU_APP_ROLE_REQUIRED", "当前飞书账号没有此应用的管理权限，请由应用所有者或管理员授权。");
        }
        return responses.requireSuccess(response, true);
    }

    public static String required(JsonNode value, String key) {
        if (!value.path(key).isTextual() || value.path(key).asText().isBlank()) {
            throw failure("FEISHU_RESPONSE_INVALID", "飞书返回的信息不完整，请稍后继续检查。");
        }
        return value.path(key).asText();
    }

    private static ChannelProviderException failure(String code, String message) {
        return new ChannelProviderException(code, message);
    }
}
