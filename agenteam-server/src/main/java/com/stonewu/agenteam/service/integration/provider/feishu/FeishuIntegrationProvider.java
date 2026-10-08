package com.stonewu.agenteam.service.integration.provider.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.response.IntegrationProviderView;
import com.stonewu.agenteam.service.integration.ChannelIdentityProvider;
import com.stonewu.agenteam.service.integration.ChannelMessageSender;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import com.stonewu.agenteam.service.integration.IntegrationProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 飞书自建应用按当前 v3 用户令牌协议授权，以应用身份给本企业成员发消息。
 */
@Component
public class FeishuIntegrationProvider implements IntegrationProvider, ChannelIdentityProvider, ChannelMessageSender {
    private final IntegrationEndpoints endpoints;
    private final IntegrationHttpClient http;
    private final ChannelResponseMapper responses;
    private final ObjectMapper json;

    public FeishuIntegrationProvider(IntegrationEndpoints endpoints, IntegrationHttpClient http,
                                     ChannelResponseMapper responses, ObjectMapper json) {
        this.endpoints = endpoints;
        this.http = http;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    public String name() {
        return "飞书";
    }

    @Override
    public List<IntegrationProviderView.Field> publicFields() {
        return List.of(new IntegrationProviderView.Field("externalAppId", "应用编号（App ID）", "text", true, null, 191, null, null));
    }

    @Override
    public List<ChannelRateLimit> rateLimits(Map<String, Object> configuration) {
        normalizeConfiguration(configuration);
        return List.of(new ChannelRateLimit("application_second", false, 1, 50, false, 0),
            new ChannelRateLimit("application_minute", false, 60, 1000, false, 0),
            new ChannelRateLimit("member_second", true, 1, 5, false, 0));
    }

    @Override
    public Duration duplicateCheckWindow() {
        return Duration.ofHours(1);
    }

    @Override
    public ChannelAccessToken fetchAccessToken(IntegrationApplication app, String secret) {
        var payload = json.createObjectNode().put("app_id", app.externalAppId()).put("app_secret", secret);
        return responses.token(http.post(app.id(), endpoints.feishu("/open-apis/auth/v3/tenant_access_token/internal"),
            Map.of(), payload, null), "tenant_access_token", "expire", true);
    }

    @Override
    public ChannelApplicationCheck checkApplication(IntegrationApplication app, ChannelAccessToken token) {
        var tenant = responses.requireSuccess(http.get(app.id(), endpoints.feishu("/open-apis/tenant/v2/tenant/query"),
            Map.of(), token.value()), true).path("data").path("tenant");
        String tenantId = responses.required(tenant, "tenant_key");
        if (app.externalTenantId() != null && !app.externalTenantId().equals(tenantId)) {
            throw new ChannelProviderException("CHANNEL_TENANT_MISMATCH", "应用对应的飞书企业与当前接入配置不一致。");
        }
        return new ChannelApplicationCheck(tenantId, responses.required(tenant, "name"));
    }

    @Override
    public URI authorizationUri(IntegrationApplication app, URI callback, String state,
                                String codeChallenge, boolean embeddedClient) {
        return IntegrationHttpClient.withQuery(endpoints.feishuAccounts("/open-apis/authen/v1/authorize"),
            Map.of("client_id", app.externalAppId(), "response_type", "code", "redirect_uri", callback.toString(),
                "state", state, "code_challenge", codeChallenge, "code_challenge_method", "S256"));
    }

    @Override
    public ChannelIdentity exchangeIdentity(IntegrationApplication app, String secret, ChannelAccessToken appToken,
                                            String code, URI callback, String codeVerifier) {
        var userToken = responses.token(http.form(app.id(), endpoints.feishuAccounts("/oauth/v3/token"),
            Map.of("grant_type", "authorization_code", "client_id", app.externalAppId(), "client_secret", secret,
                "code", code, "redirect_uri", callback.toString(), "code_verifier", codeVerifier)),
            "access_token", "expires_in", true);
        return responses.feishuIdentity(http.get(app.id(), endpoints.feishu("/open-apis/authen/v1/user_info"),
            Map.of(), userToken.value()), app.externalTenantId());
    }

    @Override
    public JsonNode render(IntegrationApplication app, String recipient, String title, String body,
                           URI detailLink, String requestId) {
        var content = json.createObjectNode().put("text", title + "\n\n" + body + "\n" + detailLink);
        return json.createObjectNode().put("receive_id", recipient).put("msg_type", "text")
            .put("content", content.toString()).put("uuid", requestId);
    }

    @Override
    public ChannelSendResult send(IntegrationApplication app, ChannelAccessToken token, JsonNode payload) {
        try {
            return responses.send(http.post(app.id(), endpoints.feishu("/open-apis/im/v1/messages"),
                Map.of("receive_id_type", "open_id"), payload, token.value()), true);
        } catch (ChannelProviderException failure) {
            return responses.unknown(failure);
        }
    }
}
