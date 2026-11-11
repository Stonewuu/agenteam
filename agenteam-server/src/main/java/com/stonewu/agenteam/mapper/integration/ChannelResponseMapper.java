package com.stonewu.agenteam.mapper.integration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 根据官方响应结构转换结果，不把非零业务错误或无效收件人当成发送成功。
 */
@Component
public class ChannelResponseMapper {
    public JsonNode requireSuccess(ChannelHttpResponse response, boolean feishu) {
        var body = response.body();
        String field = feishu ? "code" : "errcode";
        if (body == null || !body.isObject() || response.status() < 200 || response.status() >= 300) {
            throw failure("CHANNEL_PLATFORM_REJECTED", Messages.text("error.integration.channelResponseMapper.requireSuccess.1"));
        }
        if (feishu && !body.has(field) || body.has(field) && (!body.path(field).isIntegralNumber()
            || body.path(field).asLong() != 0) || body.hasNonNull("error")) {
            throw failure("CHANNEL_PLATFORM_REJECTED", Messages.text("error.integration.channelResponseMapper.requireSuccess.2"));
        }
        return body;
    }

    public ChannelAccessToken token(ChannelHttpResponse response, String field, String expiry, boolean feishu) {
        var body = requireSuccess(response, feishu);
        String token = required(body, field);
        if (!body.path(expiry).isIntegralNumber() || body.path(expiry).asLong() <= 0) {
            throw failure("CHANNEL_RESPONSE_INVALID", Messages.text("error.integration.channelResponseMapper.token.1"));
        }
        return new ChannelAccessToken(token, body.path(expiry).asLong());
    }

    public ChannelIdentity wecomIdentity(ChannelHttpResponse response, String expectedTenant) {
        var body = requireSuccess(response, false);
        String subject = optional(body, "userid");
        if (subject == null || subject.contains("/") || body.hasNonNull("openid")
            || body.hasNonNull("external_userid")) {
            throw failure("CHANNEL_MEMBER_REQUIRED", Messages.text("error.integration.channelResponseMapper.wecomIdentity.1"));
        }
        return new ChannelIdentity(expectedTenant, "wecom_userid", subject, null, null);
    }

    public ChannelIdentity feishuIdentity(ChannelHttpResponse response, String expectedTenant) {
        var data = requireSuccess(response, true).path("data");
        String tenant = required(data, "tenant_key");
        if (!tenant.equals(expectedTenant)) {
            throw failure("CHANNEL_TENANT_MISMATCH", Messages.text("error.integration.channelResponseMapper.feishuIdentity.1"));
        }
        return new ChannelIdentity(tenant, "feishu_open_id", required(data, "open_id"),
            optional(data, "union_id"), optional(data, "name"));
    }

    public ChannelSendResult send(ChannelHttpResponse response, boolean feishu) {
        JsonNode body = response.body();
        String field = feishu ? "code" : "errcode";
        String code = body != null && body.path(field).isIntegralNumber() ? body.path(field).asText() : null;
        if (response.status() >= 500) {
            return result(Outcome.UNKNOWN, code, null, "CHANNEL_SEND_UNKNOWN", Messages.text("text.integration.channelResponseMapper.send.1"), response);
        }
        if (feishu && "230049".equals(code)) {
            return result(Outcome.UNKNOWN, code, null, "CHANNEL_SEND_UNKNOWN", Messages.text("text.integration.channelResponseMapper.send.2"), response);
        }
        if (response.status() == 429 || feishu && Set.of("99991400", "230020").contains(code == null ? "" : code)
            || !feishu && "45009".equals(code)) {
            return result(Outcome.RETRYABLE_FAILURE, code, null, "CHANNEL_RATE_LIMITED", Messages.text("text.integration.channelResponseMapper.send.3"), response);
        }
        if (feishu && Set.of("99991663", "99991665").contains(code == null ? "" : code)
            || !feishu && Set.of("40014", "42001").contains(code == null ? "" : code)) {
            return result(Outcome.TOKEN_EXPIRED, code, null, "CHANNEL_TOKEN_EXPIRED", Messages.text("text.integration.channelResponseMapper.send.4"), response);
        }
        if (code == null) {
            return result(Outcome.UNKNOWN, null, null, "CHANNEL_SEND_UNKNOWN", Messages.text("text.integration.channelResponseMapper.send.5"), response);
        }
        if (!code.equals("0") || response.status() < 200 || response.status() >= 300) {
            return result(Outcome.PERMANENT_FAILURE, code, null, "CHANNEL_SEND_REJECTED", description(code), response);
        }
        if (!feishu && (present(body, "invaliduser") || present(body, "invalidparty") || present(body, "invalidtag")
            || present(body, "unlicenseduser"))) {
            return result(Outcome.PERMANENT_FAILURE, code, null, "CHANNEL_RECIPIENT_UNAVAILABLE",
                Messages.text("text.integration.channelResponseMapper.send.6"), response);
        }
        String message = optional(feishu ? body.path("data") : body, feishu ? "message_id" : "msgid");
        if (message == null) {
            return result(Outcome.UNKNOWN, code, null, "CHANNEL_SEND_UNKNOWN", Messages.text("text.integration.channelResponseMapper.send.7"), response);
        }
        return result(Outcome.ACCEPTED, code, message, null, null, response);
    }

    public ChannelSendResult unknown(ChannelProviderException failure) {
        return new ChannelSendResult(Outcome.UNKNOWN, null, null, failure.code(),
            Messages.text("text.integration.channelResponseMapper.unknown.1"), null, null, null);
    }

    public String required(JsonNode body, String name) {
        String value = optional(body, name);
        if (value == null) {
            throw failure("CHANNEL_RESPONSE_INVALID", Messages.text("error.integration.channelResponseMapper.required.1"));
        }
        return value;
    }

    public String optional(JsonNode body, String name) {
        var value = body.path(name);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private boolean present(JsonNode body, String field) {
        var value = body.path(field);
        return !value.isMissingNode() && !value.isNull() && (!value.isTextual() || !value.asText().isEmpty());
    }

    private ChannelSendResult result(Outcome outcome, String code, String message, String error, String summary,
                                     ChannelHttpResponse response) {
        return new ChannelSendResult(outcome, code, message, error, summary, response.retryAfterSeconds(),
            response.status(), response.traceId());
    }

    private String description(String code) {
        return switch (code) {
            case "230013", "81013" -> Messages.text("text.integration.channelResponseMapper.description.1");
            case "230029" -> Messages.text("text.integration.channelResponseMapper.description.2");
            case "230034" -> Messages.text("text.integration.channelResponseMapper.description.3");
            case "230053" -> Messages.text("text.integration.channelResponseMapper.description.4");
            case "230027", "48002" -> Messages.text("text.integration.channelResponseMapper.description.5");
            case "230022", "230028" -> Messages.text("text.integration.channelResponseMapper.description.6");
            default -> Messages.text("text.integration.channelResponseMapper.description.7");
        };
    }

    private ChannelProviderException failure(String code, String summary) {
        return new ChannelProviderException(code, summary);
    }
}
