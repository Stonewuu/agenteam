package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 长连接响应沿用 HTTP（网络请求）诊断的凭据和正文脱敏策略。 */
@Component
public class IntegrationFrameDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(IntegrationFrameDiagnostics.class);
    private final ObjectMapper json;
    public IntegrationFrameDiagnostics(ObjectMapper json) {
        this.json = json;
    }

    public void response(String connection, String command, JsonNode request, JsonNode response) {
        var diagnostics = new ChannelRequestDiagnostics(Map.of(), request, null, json);
        if (command.equals("aibot_upload_media_chunk") && request != null
            && (!response.path("errcode").isIntegralNumber() || response.path("errcode").asLong() != 0)) {
            String encoded = request.path("base64_data").asText("");
            // 平台可能回显解码后的文件行；只处理服务端生成的合法、有界分片，不打印原文。
            if (!encoded.isEmpty() && encoded.length() <= 1_048_576 && encoded.length() % 4 == 0
                && encoded.matches("[A-Za-z0-9+/]+={0,2}")) {
                diagnostics.fileContent(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            }
        }
        diagnostics.frame(connection, command, response);
    }

    public static void networkFailure(String connection, String operation, Throwable failure) {
        LOG.warn("平台长连接网络操作失败，接入={}，操作={}", connection, operation, ChannelRequestDiagnostics.networkCause(failure));
    }
}
