package com.stonewu.agenteam.service.integration.provider.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.response.IntegrationProviderView;
import com.stonewu.agenteam.service.integration.ChannelIdentityProvider;
import com.stonewu.agenteam.service.integration.ChannelMessageSender;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import com.stonewu.agenteam.service.integration.IntegrationProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 企业微信自建应用授权与应用消息，严格使用内部成员身份。
 */
@Component
public class WecomIntegrationProvider implements IntegrationProvider, ChannelIdentityProvider, ChannelMessageSender {
    private final IntegrationEndpoints endpoints;
    private final IntegrationHttpClient http;
    private final ChannelResponseMapper responses;
    private final ObjectMapper json;

    public WecomIntegrationProvider(IntegrationEndpoints endpoints, IntegrationHttpClient http,
                                    ChannelResponseMapper responses, ObjectMapper json) {
        this.endpoints = endpoints;
        this.http = http;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public String code() {
        return "wecom";
    }

    @Override
    public String name() {
        return "企业微信";
    }

    @Override
    public List<IntegrationProviderView.Field> publicFields() {
        return List.of(new IntegrationProviderView.Field("externalTenantId", "企业编号（CorpID）", "text", true, null, 191, null, null),
            new IntegrationProviderView.Field("externalAppId", "应用编号（AgentId）", "text", true, null, 191, null, null),
            new IntegrationProviderView.Field("configuration.dailyMessageLimit", "每日通知发送上限", "number", true, 1, 100_000_000, 200,
                "按企业微信后台允许的每日消息量填写。"));
    }

    @Override
    public void validateApplicationId(String value) {
        if (!value.matches("[1-9][0-9]{0,9}")) {
            throw ApiException.invalidField("externalAppId", "企业微信应用编号需要填写正整数。");
        }
    }

    @Override
    public Map<String, Object> normalizeConfiguration(Map<String, Object> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return Map.of("dailyMessageLimit", 200);
        }
        if (!Set.of("dailyMessageLimit").containsAll(configuration.keySet())) {
            throw ApiException.invalidField("configuration", "企业微信仅支持设置每日通知发送上限。");
        }
        JsonNode limit = json.valueToTree(configuration).path("dailyMessageLimit");
        if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue() < 1 || limit.intValue() > 100_000_000) {
            throw ApiException.invalidField("configuration.dailyMessageLimit", "每日发送上限需要填写 1 至 100000000 的整数。");
        }
        return Map.of("dailyMessageLimit", limit.intValue());
    }

    @Override
    public List<ChannelRateLimit> rateLimits(Map<String, Object> configuration) {
        int daily = ((Number) normalizeConfiguration(configuration).get("dailyMessageLimit")).intValue();
        return List.of(new ChannelRateLimit("member_minute", true, 60, 30, false, 0),
            new ChannelRateLimit("member_hour", true, 3600, 1000, false, 0),
            new ChannelRateLimit("application_day", false, 86400, daily, true, 8 * 3600));
    }

    @Override
    public Duration duplicateCheckWindow() {
        return Duration.ofHours(1);
    }

    @Override
    public ChannelAccessToken fetchAccessToken(IntegrationApplication app, String secret) {
        return responses.token(http.get(app.id(), endpoints.wecom("/cgi-bin/gettoken"),
            Map.of("corpid", app.externalTenantId(), "corpsecret", secret), null), "access_token", "expires_in", false);
    }

    @Override
    public ChannelApplicationCheck checkApplication(IntegrationApplication app, ChannelAccessToken token) {
        var body = responses.requireSuccess(http.get(app.id(), endpoints.wecom("/cgi-bin/agent/get"),
            Map.of("access_token", token.value(), "agentid", app.externalAppId()), null), false);
        if (!body.path("agentid").isIntegralNumber() || !body.path("agentid").asText().equals(app.externalAppId())) {
            throw new ChannelProviderException("CHANNEL_APP_MISMATCH", "应用密钥与填写的企业微信应用编号不匹配。");
        }
        return new ChannelApplicationCheck(app.externalTenantId(), responses.required(body, "name"));
    }

    @Override
    public URI authorizationUri(IntegrationApplication app, URI callback, String state,
                                String codeChallenge, boolean embeddedClient) {
        if (embeddedClient) {
            var base = IntegrationHttpClient.withQuery(URI.create("https://open.weixin.qq.com/connect/oauth2/authorize"),
                Map.of("appid", app.externalTenantId(), "agentid", app.externalAppId(), "redirect_uri", callback.toString(),
                    "response_type", "code", "scope", "snsapi_base", "state", state));
            return URI.create(base + "#wechat_redirect");
        }
        return IntegrationHttpClient.withQuery(URI.create("https://login.work.weixin.qq.com/wwlogin/sso/login"),
            Map.of("login_type", "CorpApp", "appid", app.externalTenantId(), "agentid", app.externalAppId(),
                "redirect_uri", callback.toString(), "state", state));
    }

    @Override
    public ChannelIdentity exchangeIdentity(IntegrationApplication app, String secret, ChannelAccessToken appToken,
                                            String code, URI callback, String codeVerifier) {
        return responses.wecomIdentity(http.get(app.id(), endpoints.wecom("/cgi-bin/auth/getuserinfo"),
            Map.of("access_token", appToken.value(), "code", code), null), app.externalTenantId());
    }

    @Override
    public JsonNode render(IntegrationApplication app, String recipient, String title, String body,
                           URI detailLink, String requestId) {
        if (recipient == null || recipient.isBlank() || recipient.equalsIgnoreCase("@all") || recipient.contains("|")) {
            throw new ChannelProviderException("CHANNEL_RECIPIENT_INVALID", "此通知只能发送给一个已绑定的成员。");
        }
        var payload = json.createObjectNode().put("touser", recipient).put("agentid", Long.parseLong(app.externalAppId()))
            .put("msgtype", "textcard").put("enable_duplicate_check", 1).put("duplicate_check_interval", 3600);
        payload.putObject("textcard").put("title", title)
            .put("description", description(body))
            .put("url", detailLink.toString()).put("btntxt", "查看详情");
        return payload;
    }

    private String description(String body) {
        var summary = new StringBuilder();
        for (int point : body.codePoints().toArray()) {
            String piece = point == '\n' ? "<br>" : HtmlUtils.htmlEscape(new String(Character.toChars(point)));
            if (summary.length() + piece.length() > 511) {
                summary.append('…');
                break;
            }
            summary.append(piece);
        }
        return summary.toString();
    }

    @Override
    public ChannelSendResult send(IntegrationApplication app, ChannelAccessToken token, JsonNode payload) {
        try {
            return responses.send(http.post(app.id(), endpoints.wecom("/cgi-bin/message/send"),
                Map.of("access_token", token.value()), payload, null), false);
        } catch (ChannelProviderException failure) {
            return responses.unknown(failure);
        }
    }
}
