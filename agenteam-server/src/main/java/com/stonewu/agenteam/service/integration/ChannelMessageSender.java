package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 渲染后的请求固定保存；重试发送相同内容，不在适配器中自行重试。
 */
public interface ChannelMessageSender {
    List<ChannelRateLimit> rateLimits(Map<String, Object> configuration);

    default Duration duplicateCheckWindow() {
        return Duration.ZERO;
    }

    JsonNode render(IntegrationApplication application, String recipient, String title,
                    String body, URI detailLink, String requestId);

    ChannelSendResult send(IntegrationApplication application, ChannelAccessToken token, JsonNode payload);
}
