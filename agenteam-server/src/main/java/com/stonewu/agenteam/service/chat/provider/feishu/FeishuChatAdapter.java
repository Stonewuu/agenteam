package com.stonewu.agenteam.service.chat.provider.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.service.chat.ChatPlatformAdapter;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.integration.IntegrationResourceHttpClient;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.chat.ChatPlatformRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** 沿用现有令牌缓存和脱敏网络层，群发送不调用个人通知业务。 */
@Component
public class FeishuChatAdapter implements ChatPlatformAdapter {
    private final IntegrationTokenService tokens;
    private final IntegrationHttpClient http;
    private final IntegrationEndpoints endpoints;
    private final ChannelResponseMapper responses;
    private final ObjectMapper json;
    private final IntegrationResourceHttpClient resources;

    public FeishuChatAdapter(IntegrationTokenService tokens, IntegrationHttpClient http, IntegrationEndpoints endpoints,
                             ChannelResponseMapper responses, ObjectMapper json, IntegrationResourceHttpClient resources) {
        this.tokens = tokens;
        this.http = http;
        this.endpoints = endpoints;
        this.responses = responses;
        this.json = json;
        this.resources = resources;
    }

    @Override
    public String providerCode() {
        return "feishu";
    }

    @Override
    public JsonNode present(JsonNode payload) {
        return FeishuMessageCards.prepare(payload);
    }

    @Override
    public List<String> splitText(String text) {
        return FeishuMarkdownParts.split(text);
    }

    @Override
    public BotIdentity identity(IntegrationApplication application) {
        var token = tokens.get(application);
        var bot = responses.requireSuccess(http.get(application.id(), endpoints.feishu("/open-apis/bot/v3/info"),
            Map.of(), token.value()), true).path("bot");
        String id = responses.required(bot, "open_id");
        var capabilities = json.createObjectNode();
        capabilities.putObject("credential").put("status", "passed");
        for (String name : new String[]{"receiveMention", "sendText", "readHistory", "threadReply", "uploadFile", "crossBotIdentity", "cardInteraction"}) {
            capabilities.putObject(name).put("status", "not_tested");
        }
        return new BotIdentity(id, bot.path("app_name").asText("飞书机器人"), capabilities);
    }

    @Override
    public ConversationPage conversations(IntegrationApplication application, String cursor) {
        var query = new HashMap<String, String>();
        query.put("page_size", "50");
        if (cursor != null && !cursor.isBlank()) {
            query.put("page_token", cursor);
        }
        var data = responses.requireSuccess(http.get(application.id(), endpoints.feishu("/open-apis/im/v1/chats"),
            query, tokens.get(application).value()), true).path("data");
        var items = new ArrayList<ConversationIdentity>();
        for (var value : data.path("items")) {
            items.add(new ConversationIdentity(responses.required(value, "chat_id"), value.path("name").asText("飞书群"), "group"));
        }
        return new ConversationPage(items, data.path("has_more").asBoolean() ? responses.required(data, "page_token") : null);
    }

    @Override
    public void checkHistory(IntegrationApplication application, String chatId) {
        responses.requireSuccess(http.get(application.id(), endpoints.feishu("/open-apis/im/v1/messages"),
            Map.of("container_id_type", "chat", "container_id", chatId, "page_size", "1"), tokens.get(application).value()), true);
    }

    @Override
    public String upload(IntegrationApplication application, UploadFile file, Runnable beforeUpload) {
        return resources.upload(application.id(), endpoints.feishu("/open-apis/im/v1/files"), tokens.get(application).value(), file, beforeUpload);
    }

    @Override
    public SendReceipt send(IntegrationApplication application, JsonNode prepared, Runnable beforeSend) {
        if ("interactive_update".equals(prepared.path("messageType").asText())) {
            return updateCard(application, prepared, beforeSend);
        }
        String reply = prepared.path("replyToMessageId").asText("");
        String path = "/open-apis/im/v1/messages";
        var query = new HashMap<String, String>();
        var payload = json.createObjectNode().put("msg_type", prepared.path("messageType").asText("text"))
            .put("content", prepared.path("content").toString()).put("uuid", prepared.path("requestId").asText());
        if (!reply.isBlank()) {
            if (!reply.matches("[A-Za-z0-9_-]{1,191}")) {
                throw new IllegalArgumentException("已准备的回复消息编号不正确");
            }
            path += "/" + reply + "/reply";
            payload.put("reply_in_thread", prepared.path("replyInThread").asBoolean(true));
        } else {
            query.put("receive_id_type", "chat_id");
            payload.put("receive_id", prepared.path("receiveId").asText());
        }
        var token = tokens.get(application);
        beforeSend.run();
        var response = http.post(application.id(), endpoints.feishu(path), query, payload, token.value());
        var result = responses.send(response, true);
        if (result.outcome() == Outcome.TOKEN_EXPIRED) {
            tokens.invalidate(application, token);
        }
        return new SendReceipt(result, response.body() == null ? null : response.body().path("data").path("thread_id").asText(null));
    }

    private SendReceipt updateCard(IntegrationApplication application, JsonNode prepared, Runnable beforeSend) {
        String message = prepared.path("updateMessageId").asText();
        if (!message.matches("[A-Za-z0-9_-]{1,191}")) {
            throw new IllegalArgumentException("待更新的卡片消息编号不正确");
        }
        var token = tokens.get(application);
        var body = json.createObjectNode().put("content", prepared.path("content").toString());
        beforeSend.run();
        var response = http.patch(application.id(), endpoints.feishu("/open-apis/im/v1/messages/" + message), body, token.value());
        if (response.status() >= 200 && response.status() < 300 && response.body() != null
                && response.body().path("code").isIntegralNumber() && response.body().path("code").asInt() == 0) {
            return new SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "0", message, null, null, null, response.status(), response.traceId()), null);
        }
        var result = responses.send(response, true);
        if (result.outcome() == Outcome.TOKEN_EXPIRED) {
            tokens.invalidate(application, token);
        }
        return new SendReceipt(result, null);
    }

    @Override
    public Duration deduplicationWindow() {
        return Duration.ofHours(1);
    }

    @Override
    public FileContentStorage.Stored download(IntegrationApplication application, String messageId, JsonNode material) {
        String key = material.path("fileKey").asText();
        String type = material.path("type").asText();
        if (!messageId.matches("[A-Za-z0-9_-]{1,191}") || !key.matches("[A-Za-z0-9_-]{1,191}")
            || !(type.equals("image") || type.equals("file"))) {
            throw ChatPlatformRegistry.unavailable();
        }
        var address = IntegrationHttpClient.withQuery(endpoints.feishu("/open-apis/im/v1/messages/" + messageId + "/resources/" + key), Map.of("type", type));
        return resources.download(application.enterpriseId(), application.id(), address, tokens.get(application).value());
    }
}
