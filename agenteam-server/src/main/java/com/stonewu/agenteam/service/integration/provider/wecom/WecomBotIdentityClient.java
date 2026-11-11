package com.stonewu.agenteam.service.integration.provider.wecom;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 从官方身份服务读取机器人与授权人；未知格式必须停止核验，不能从任意说明文字猜测身份。 */
@Component
public class WecomBotIdentityClient {
    private static final Pattern IDENTITY = Pattern.compile(
        Messages.source("protocol.integration.wecomBotIdentityClient.IDENTITY.1"));
    private static final Pattern ID_LINE = Pattern.compile("(?m)^ID：");
    private final IntegrationEndpoints endpoints;
    private final IntegrationHttpClient http;
    private final ObjectMapper json;
    private final Clock clock;

    public WecomBotIdentityClient(IntegrationEndpoints endpoints, IntegrationHttpClient http, ObjectMapper json, Clock clock) {
        this.endpoints = endpoints;
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    public Identity query(String jobId, String botId, String secret) {
        long time = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var request = json.createObjectNode().put("bot_id", botId).put("time", time).put("nonce", nonce)
            .put("signature", signature(secret + botId + time + nonce)).put("bind_source", 2);
        var config = success(http.post(jobId, endpoints.wecom("/cgi-bin/aibot/cli/get_cli_config"), Map.of(), request, null));
        String token = required(config, "token");
        var response = success(http.post(jobId, endpoints.wecom("/cli/identity/whoami"), Map.of(),
            json.createObjectNode().put("payload", "{}"), token));
        try {
            var envelope = json.readTree(required(response, "results_json"));
            var result = json.readTree(required(envelope, "result"));
            return parse(botId, required(result, "extra_identity_context"));
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    public Identity parse(String expectedBot, String context) {
        String normalized = context.replace("\r\n", "\n").strip();
        var match = IDENTITY.matcher(normalized);
        if (normalized.length() > 16384 || !normalized.endsWith("</extra_identity_context>") || !match.find()
            || ID_LINE.matcher(normalized).results().count() != 2
            || normalized.indexOf(Messages.source("protocol.integration.wecomBotIdentityClient.parse.1")) != normalized.lastIndexOf(Messages.source("protocol.integration.wecomBotIdentityClient.parse.1"))
            || normalized.indexOf(Messages.source("protocol.integration.wecomBotIdentityClient.parse.2")) != normalized.lastIndexOf(Messages.source("protocol.integration.wecomBotIdentityClient.parse.2"))
            || !expectedBot.equals(match.group(2))) {
            throw unavailable();
        }
        // 只读取固定位置字段，后续权限说明或指令文字不参与任何业务决策。
        return new Identity(match.group(2), match.group(1).strip(), match.group(3));
    }

    private static String signature(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(Messages.text("error.integration.wecomBotIdentityClient.signature.1"), failure);
        }
    }

    private static JsonNode success(ChannelHttpResponse response) {
        var body = response.body();
        if (response.status() < 200 || response.status() >= 300 || body == null || !body.path("errcode").isIntegralNumber()
            || body.path("errcode").asLong() != 0) {
            throw unavailable();
        }
        return body;
    }

    private static String required(JsonNode body, String field) {
        if (body == null || !body.path(field).isTextual() || body.path(field).asText().isBlank()) {
            throw unavailable();
        }
        return body.path(field).asText();
    }

    private static ChannelProviderException unavailable() {
        return new ChannelProviderException("WECOM_BOT_IDENTITY_UNAVAILABLE", Messages.text("error.integration.wecomBotIdentityClient.unavailable.1"));
    }

    public record Identity(String botId, String name, String encryptedOwnerId) {
    }
}
