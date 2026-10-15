package com.stonewu.agenteam.service.chat.provider.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.ChatConnectionRegistry;
import com.stonewu.agenteam.service.chat.ChatPlatformAdapter;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.WecomResourceDownloader;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.Arrays;
import java.io.IOException;

/** 企微主动消息通过当前持有的认证连接发送，不依赖另一个机器人收到原回调。 */
@Component
public class WecomAiBotChatAdapter implements ChatPlatformAdapter {
    private final ChatConnectionRegistry connections;
    private final IntegrationQueryService integrations;
    private final ObjectMapper json;
    private final WecomResourceDownloader resources;
    public WecomAiBotChatAdapter(ChatConnectionRegistry connections, IntegrationQueryService integrations, ObjectMapper json, WecomResourceDownloader resources) {
        this.connections = connections;
        this.integrations = integrations;
        this.json = json;
        this.resources = resources;
    }

    @Override
    public String providerCode() {
        return "wecom_aibot";
    }

    @Override
    public BotIdentity identity(IntegrationApplication application) {
        var app = integrations.require(application.enterpriseId(), application.id());
        if (!app.getLastCheckStatus().equals("passed") || app.getIdentityVerifiedAt() == null) {
            throw new ChannelProviderException("INTEGRATION_CHECK_REQUIRED", "请先检查企微智能机器人凭据。");
        }
        var capabilities = json.createObjectNode();
        capabilities.putObject("credential").put("status", "passed");
        for (String name : new String[]{"receiveMention", "sendText", "uploadFile"}) {
            capabilities.putObject(name).put("status", "not_tested");
        }
        for (String name : new String[]{"crossBotIdentity", "readHistory", "threadReply"}) {
            capabilities.putObject(name).put("status", "unsupported");
        }
        return new BotIdentity(application.externalAppId(), app.getName(), capabilities);
    }

    @Override
    public ConversationPage conversations(IntegrationApplication application, String cursor) {
        return new ConversationPage(List.of(), null);
    }

    @Override
    public Downloaded fetch(IntegrationApplication application, String messageId, JsonNode material, String name) {
        return resources.fetch(application, material);
    }

    @Override
    public String upload(IntegrationApplication application, UploadFile file, Runnable beforeUpload) {
        var owned = connections.require(application);
        byte[] bytes;
        try (var stream = file.open().get()) {
            bytes = stream.readNBytes(20 * 1024 * 1024 + 1);
        } catch (IOException failure) {
            throw new ChannelProviderException("CHAT_FILE_UPLOAD_FAILED", "成果文件暂时无法读取。", failure);
        }
        if (bytes.length < 1 || bytes.length > 20 * 1024 * 1024 || bytes.length != file.size()) {
            throw new ChannelProviderException("CHAT_RESOURCE_TOO_LARGE", "成果文件大小不符合发送要求。");
        }
        int chunks = (bytes.length + 524287) / 524288;
        Runnable guard = () -> {
            owned.guard().run();
            beforeUpload.run();
        };
        var start = owned.connection().request("upload-init-" + UUID.randomUUID(), "aibot_upload_media_init",
            json.createObjectNode().put("type", "file").put("filename", file.name()).put("total_size", bytes.length)
                .put("total_chunks", chunks).put("md5", DigestUtils.md5DigestAsHex(bytes)), guard);
        String upload = field(start, "upload_id");
        for (int index = 0; index < chunks; index++) {
            byte[] part = Arrays.copyOfRange(bytes, index * 524288, Math.min(bytes.length, (index + 1) * 524288));
            var response = owned.connection().request("upload-part-" + UUID.randomUUID(), "aibot_upload_media_chunk",
                json.createObjectNode().put("upload_id", upload).put("chunk_index", index)
                    .put("base64_data", Base64.getEncoder().encodeToString(part)), guard);
            successful(response);
        }
        return field(owned.connection().request("upload-finish-" + UUID.randomUUID(), "aibot_upload_media_finish",
            json.createObjectNode().put("upload_id", upload), guard), "media_id");
    }

    private void successful(JsonNode response) {
        if (!response.path("errcode").isIntegralNumber() || response.path("errcode").asLong() != 0) {
            throw new ChannelProviderException("CHAT_FILE_UPLOAD_REJECTED", "企微没有接受本次文件上传。");
        }
    }

    private String field(JsonNode response, String name) {
        successful(response);
        String value = response.path("body").path(name).asText();
        if (value.isBlank() || value.length() > 512) {
            throw new ChannelProviderException("CHAT_FILE_UPLOAD_INVALID", "企微未返回有效的文件上传编号。");
        }
        return value;
    }

    @Override
    public SendReceipt send(IntegrationApplication application, JsonNode preparedPayload, Runnable beforeSend) {
        var owned = connections.require(application);
        var body = json.createObjectNode().put("chatid", preparedPayload.path("receiveId").asText()).put("msgtype", "markdown");
        body.putObject("markdown").put("content", preparedPayload.path("content").path("text").asText());
        if (preparedPayload.path("messageType").asText().equals("file")) {
            body.remove("markdown");
            body.put("msgtype", "file");
            body.putObject("file").put("media_id", preparedPayload.path("content").path("media_id").asText());
        }
        var response = owned.connection().request(preparedPayload.path("requestId").asText(), "aibot_send_msg", body, () -> {
            owned.guard().run();
            beforeSend.run();
        });
        String code = response.path("errcode").isIntegralNumber() ? response.path("errcode").asText() : null;
        Outcome outcome = code == null ? Outcome.UNKNOWN : code.equals("0") ? Outcome.ACCEPTED
            : code.equals("45009") ? Outcome.RETRYABLE_FAILURE : Outcome.PERMANENT_FAILURE;
        String messageId = response.path("body").path("msgid").asText(null);
        return new SendReceipt(new ChannelSendResult(outcome, code, messageId,
            outcome == Outcome.ACCEPTED ? null : outcome == Outcome.UNKNOWN ? "CHAT_SEND_UNKNOWN" : "CHAT_SEND_REJECTED",
            outcome == Outcome.ACCEPTED ? null : "企微尚未接受此消息，请查看发送记录。", null, null,
            response.path("headers").path("req_id").asText(null)), null);
    }
}
