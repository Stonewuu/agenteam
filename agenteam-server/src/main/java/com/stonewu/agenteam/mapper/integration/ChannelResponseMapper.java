package com.stonewu.agenteam.mapper.integration;

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
            throw failure("CHANNEL_PLATFORM_REJECTED", "平台没有接受本次请求，请检查应用配置和授权状态。");
        }
        if (feishu && !body.has(field) || body.has(field) && (!body.path(field).isIntegralNumber()
            || body.path(field).asLong() != 0) || body.hasNonNull("error")) {
            throw failure("CHANNEL_PLATFORM_REJECTED", "平台校验失败，请检查应用凭证、权限或重新授权。");
        }
        return body;
    }

    public ChannelAccessToken token(ChannelHttpResponse response, String field, String expiry, boolean feishu) {
        var body = requireSuccess(response, feishu);
        String token = required(body, field);
        if (!body.path(expiry).isIntegralNumber() || body.path(expiry).asLong() <= 0) {
            throw failure("CHANNEL_RESPONSE_INVALID", "平台没有返回有效的凭证有效期。");
        }
        return new ChannelAccessToken(token, body.path(expiry).asLong());
    }

    public ChannelIdentity wecomIdentity(ChannelHttpResponse response, String expectedTenant) {
        var body = requireSuccess(response, false);
        String subject = optional(body, "userid");
        if (subject == null || subject.contains("/") || body.hasNonNull("openid")
            || body.hasNonNull("external_userid")) {
            throw failure("CHANNEL_MEMBER_REQUIRED", "请使用当前企业的内部成员账号完成授权。");
        }
        return new ChannelIdentity(expectedTenant, "wecom_userid", subject, null, null);
    }

    public ChannelIdentity feishuIdentity(ChannelHttpResponse response, String expectedTenant) {
        var data = requireSuccess(response, true).path("data");
        String tenant = required(data, "tenant_key");
        if (!tenant.equals(expectedTenant)) {
            throw failure("CHANNEL_TENANT_MISMATCH", "授权账号不属于此接入配置对应的企业。");
        }
        return new ChannelIdentity(tenant, "feishu_open_id", required(data, "open_id"),
            optional(data, "union_id"), optional(data, "name"));
    }

    public ChannelSendResult send(ChannelHttpResponse response, boolean feishu) {
        JsonNode body = response.body();
        String field = feishu ? "code" : "errcode";
        String code = body != null && body.path(field).isIntegralNumber() ? body.path(field).asText() : null;
        if (response.status() >= 500) {
            return result(Outcome.UNKNOWN, code, null, "CHANNEL_SEND_UNKNOWN", "平台暂时无法确认是否已接受消息。", response);
        }
        if (feishu && "230049".equals(code)) {
            return result(Outcome.UNKNOWN, code, null, "CHANNEL_SEND_UNKNOWN", "平台仍在处理该消息，暂时无法确认结果。", response);
        }
        if (response.status() == 429 || feishu && Set.of("99991400", "230020").contains(code == null ? "" : code)
            || !feishu && "45009".equals(code)) {
            return result(Outcome.RETRYABLE_FAILURE, code, null, "CHANNEL_RATE_LIMITED", "平台要求降低发送频率，稍后继续。", response);
        }
        if (feishu && Set.of("99991663", "99991665").contains(code == null ? "" : code)
            || !feishu && Set.of("40014", "42001").contains(code == null ? "" : code)) {
            return result(Outcome.TOKEN_EXPIRED, code, null, "CHANNEL_TOKEN_EXPIRED", "应用访问凭证已失效。", response);
        }
        if (code == null) {
            return result(Outcome.UNKNOWN, null, null, "CHANNEL_SEND_UNKNOWN", "平台没有返回可确认的发送结果。", response);
        }
        if (!code.equals("0") || response.status() < 200 || response.status() >= 300) {
            return result(Outcome.PERMANENT_FAILURE, code, null, "CHANNEL_SEND_REJECTED", description(code), response);
        }
        if (!feishu && (present(body, "invaliduser") || present(body, "invalidparty") || present(body, "invalidtag")
            || present(body, "unlicenseduser"))) {
            return result(Outcome.PERMANENT_FAILURE, code, null, "CHANNEL_RECIPIENT_UNAVAILABLE",
                "接收成员不存在、不在应用可见范围内或缺少应用使用许可。", response);
        }
        String message = optional(feishu ? body.path("data") : body, feishu ? "message_id" : "msgid");
        if (message == null) {
            return result(Outcome.UNKNOWN, code, null, "CHANNEL_SEND_UNKNOWN", "平台没有返回消息编号，暂时无法确认结果。", response);
        }
        return result(Outcome.ACCEPTED, code, message, null, null, response);
    }

    public ChannelSendResult unknown(ChannelProviderException failure) {
        return new ChannelSendResult(Outcome.UNKNOWN, null, null, failure.code(),
            "发送请求未取得明确结果，消息可能已经提交。", null, null, null);
    }

    public String required(JsonNode body, String name) {
        String value = optional(body, name);
        if (value == null) {
            throw failure("CHANNEL_RESPONSE_INVALID", "平台返回结果缺少必要信息，请重新检查接入配置。");
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
            case "230013", "81013" -> "接收成员不在应用可用范围内，或已被停用。";
            case "230029" -> "接收成员已离职，无法发送消息。";
            case "230034" -> "接收成员的渠道身份已失效，请重新绑定。";
            case "230053" -> "接收成员已关闭机器人消息。";
            case "230027", "48002" -> "应用没有发送消息所需的权限。";
            case "230022", "230028" -> "平台拒绝了当前消息内容，请修改后重试。";
            default -> "平台拒绝发送，请检查应用权限、接收人和消息内容。";
        };
    }

    private ChannelProviderException failure(String code, String summary) {
        return new ChannelProviderException(code, summary);
    }
}
