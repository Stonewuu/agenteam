package com.stonewu.agenteam.service.integration.provider.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 统一企微成员编号；调用前仍须单独确认机器人所属企业，查到同名成员不能代替企业核验。 */
@Component
public class WecomMemberClient {
    private final IntegrationEndpoints endpoints;
    private final IntegrationHttpClient http;
    private final ObjectMapper json;

    public WecomMemberClient(IntegrationEndpoints endpoints, IntegrationHttpClient http, ObjectMapper json) {
        this.endpoints = endpoints;
        this.http = http;
        this.json = json;
    }

    public Member resolve(IntegrationApplication enterprise, ChannelAccessToken token, String sender, String reportedTenant) {
        return resolve(enterprise, token, sender, reportedTenant, false);
    }

    /** 企业归属检查只接受企微完成的加密编号转换，不能用明文成员查询代替。 */
    public Member resolveEncrypted(IntegrationApplication enterprise, ChannelAccessToken token, String sender) {
        return resolve(enterprise, token, sender, null, true);
    }

    private Member resolve(IntegrationApplication enterprise, ChannelAccessToken token, String sender, String reportedTenant, boolean requireEncrypted) {
        if (!"wecom".equals(enterprise.providerCode()) || enterprise.externalTenantId() == null
            || enterprise.externalTenantId().isBlank() || sender == null || sender.isBlank() || sender.length() > 191) {
            throw rejected("WECOM_MEMBER_INVALID", "企微成员信息不完整，暂时无法识别账号。");
        }
        if (reportedTenant != null && !reportedTenant.isBlank() && !enterprise.externalTenantId().equals(reportedTenant)) {
            throw rejected("WECOM_ENTERPRISE_MISMATCH", "此消息不属于已接入的企微企业。");
        }
        var body = json.createObjectNode();
        body.putArray("open_userid_list").add(sender);
        var converted = success(http.post(enterprise.id(), endpoints.wecom("/cgi-bin/batch/openuserid_to_userid"),
            Map.of("access_token", token.value()), body, null));
        String canonical = null;
        for (var item : converted.path("userid_list")) {
            if (!sender.equals(item.path("open_userid").asText()) || canonical != null) {
                throw invalid();
            }
            canonical = identifier(item, "userid");
        }
        boolean encrypted = canonical != null;
        int invalidCount = 0;
        for (var invalid : converted.path("invalid_open_userid_list")) {
            if (!invalid.isTextual() || !sender.equals(invalid.asText())) {
                throw invalid();
            }
            invalidCount++;
        }
        if (encrypted && invalidCount != 0 || !encrypted && invalidCount != 1) {
            throw invalid();
        }
        if (requireEncrypted && !encrypted) {
            throw rejected("WECOM_ENTERPRISE_UNCONFIRMED", "无法确认机器人属于当前企业，请检查扫码时选择的企业和应用可见范围。");
        }
        // 已经是明文的编号也会出现在无效转换列表；只有企业成员接口确认后才接受。
        if (!encrypted) {
            canonical = sender;
        }
        var member = success(http.get(enterprise.id(), endpoints.wecom("/cgi-bin/user/get"),
            Map.of("access_token", token.value(), "userid", canonical), null));
        if (!canonical.equals(identifier(member, "userid")) || member.hasNonNull("external_userid")) {
            throw invalid();
        }
        if (!member.path("status").isIntegralNumber() || member.path("status").asInt() != 1) {
            throw rejected("WECOM_MEMBER_INACTIVE", "此企微成员尚未激活或已停用，暂时不能使用智能体。");
        }
        return new Member(canonical, member.path("name").isTextual() ? member.path("name").asText() : null, encrypted);
    }

    private static JsonNode success(ChannelHttpResponse response) {
        var body = response.body();
        if (body == null || response.status() < 200 || response.status() >= 300 || !body.path("errcode").isIntegralNumber()) {
            throw invalid();
        }
        long code = body.path("errcode").asLong();
        if (code == 40014 || code == 42001) {
            throw rejected("CHANNEL_TOKEN_EXPIRED", "企微应用凭据已过期，请稍后重试。");
        }
        if (code != 0) {
            throw rejected("WECOM_MEMBER_UNAVAILABLE", "无法核对企微成员，请检查企业应用的通讯录权限和可见范围。");
        }
        return body;
    }

    private static String identifier(JsonNode value, String key) {
        var id = value.path(key);
        if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 191
            || id.asText().codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return id.asText();
    }

    private static ChannelProviderException invalid() {
        return rejected("WECOM_MEMBER_RESPONSE_INVALID", "企微返回的成员信息不完整，暂时无法识别账号。");
    }

    private static ChannelProviderException rejected(String code, String message) {
        return new ChannelProviderException(code, message);
    }

    public record Member(String userId, String displayName, boolean encryptedSource) {
    }
}
