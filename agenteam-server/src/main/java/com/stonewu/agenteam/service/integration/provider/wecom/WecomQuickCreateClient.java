package com.stonewu.agenteam.service.integration.provider.wecom;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** 使用企微官方扫码协议；创建结果只证明取得了凭据，不证明企业归属或名称。 */
@Component
public class WecomQuickCreateClient {
    private final IntegrationEndpoints endpoints;
    private final IntegrationHttpClient http;

    public WecomQuickCreateClient(IntegrationEndpoints endpoints, IntegrationHttpClient http) {
        this.endpoints = endpoints;
        this.http = http;
    }

    public Registration register(String jobId) {
        var data = data(http.get(jobId, endpoints.wecomWork("/ai/qc/generate"),
            Map.of("source", "agenteam", "plat", "0"), null));
        String code = text(data, "scode", 512);
        String url = text(data, "auth_url", 4096);
        // 不向二维码或跳转入口透传平台响应中的任意主机。
        try {
            URI address = URI.create(url);
            if (!"https".equals(address.getScheme()) || !"work.weixin.qq.com".equals(address.getHost())
                || address.getUserInfo() != null || address.getPort() != -1 || !"/ai/qc/c".equals(address.getPath())
                || address.getFragment() != null) {
                throw invalid();
            }
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        return new Registration(code, url, null, null);
    }

    public Registration poll(String jobId, Registration registration) {
        var data = data(http.get(jobId, endpoints.wecomWork("/ai/qc/query_result"),
            Map.of("scode", registration.code()), null));
        String status = text(data, "status", 64);
        if ("success".equals(status)) {
            var bot = data.path("bot_info");
            return new Registration(registration.code(), registration.url(), text(bot, "botid", 191), text(bot, "secret", 4096));
        }
        if (Set.of("expired", "cancelled", "canceled", "rejected").contains(status)) {
            throw new ChannelProviderException("WECOM_SETUP_EXPIRED", Messages.text("error.integration.wecomQuickCreateClient.poll.1"));
        }
        // 未成功的状态不得当作创建完成，任务自身负责限制等待时间。
        return registration;
    }

    public URI page(Registration registration) {
        return IntegrationHttpClient.withQuery(URI.create("https://work.weixin.qq.com/ai/qc/gen"),
            Map.of("source", "agenteam", "scode", registration.code()));
    }

    private static JsonNode data(ChannelHttpResponse response) {
        var body = response.body();
        if (response.status() < 200 || response.status() >= 300 || body == null || !body.path("data").isObject()
            || body.has("errcode") && (!body.path("errcode").isIntegralNumber() || body.path("errcode").asLong() != 0)) {
            throw invalid();
        }
        return body.path("data");
    }

    private static String text(JsonNode body, String field, int maximum) {
        var value = body.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum
            || value.asText().codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value.asText();
    }

    private static ChannelProviderException invalid() {
        return new ChannelProviderException("WECOM_SETUP_RESPONSE_INVALID", Messages.text("error.integration.wecomQuickCreateClient.invalid.1"));
    }

    public record Registration(String code, String url, String botId, String secret) {
        @Override
        public String toString() {
            return Messages.log("log.integration.wecomQuickCreateClient.toString.1");
        }
    }
}
