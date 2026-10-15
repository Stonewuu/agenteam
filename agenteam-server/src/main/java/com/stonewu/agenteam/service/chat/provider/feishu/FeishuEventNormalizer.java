package com.stonewu.agenteam.service.chat.provider.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Kind;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Mention;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Resource;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.ChatEventNormalizer;
import com.stonewu.agenteam.service.chat.ChatInputValidation;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 飞书各类消息与成员事件统一处理，机器人正文不会被认作人类指令。 */
@Component
public class FeishuEventNormalizer implements ChatEventNormalizer {
    private final ObjectMapper json;

    public FeishuEventNormalizer(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String providerCode() {
        return "feishu";
    }

    @Override
    public NormalizedChatEvent normalize(IntegrationApplication app, String ownBotId, JsonNode envelope,
                                         Instant receivedAt) {
        var header = envelope.path("header");
        var event = envelope.has("event") ? envelope.path("event") : envelope;
        verifyScope(app, header);
        verifyScope(app, event);
        String type = header.path("event_type").asText(event.path("event_type").asText("unknown"));
        String eventId = header.path("event_id").asText(event.path("event_id").asText(""));
        Kind kind = switch (type) {
            case "im.message.receive_v1" -> Kind.MESSAGE;
            case "im.chat.member.bot.added_v1" -> Kind.BOT_JOINED;
            case "im.chat.member.bot.deleted_v1" -> Kind.BOT_LEFT;
            case "im.chat.updated_v1", "im.chat.disbanded_v1" -> Kind.CHAT_UPDATED;
            case "im.message.recalled_v1" -> Kind.MESSAGE_RECALLED;
            case "im.message.reaction.created_v1", "im.message.reaction.deleted_v1" -> Kind.FEEDBACK;
            default -> Kind.IGNORED;
        };
        if (kind != Kind.MESSAGE) {
            return nonMessage(type, kind, eventId, event,
                ChatInputValidation.time(header.path("create_time").asText(), receivedAt));
        }
        var message = event.path("message");
        var sender = event.path("sender");
        String messageId = ChatInputValidation.required(message, "message_id");
        String chatId = ChatInputValidation.required(message, "chat_id");
        String chatKind = message.path("chat_type").asText();
        if (!Set.of("group", "p2p").contains(chatKind)) {
            throw ApiException.invalidField("message", "平台消息的会话类型无法识别。");
        }
        String senderId = ChatInputValidation.optional(sender.path("sender_id"), "open_id");
        String senderKind = switch (sender.path("sender_type").asText()) {
            case "user" -> senderId == null ? "unknown" : "user";
            case "app", "bot" -> senderId != null && senderId.equals(ownBotId) ? "self_bot" : "other_bot";
            default -> "unknown";
        };
        String messageType = message.path("message_type").asText("unknown");
        var content = ChatInputValidation.content(json, message.path("content").asText("{}"), app.id(), messageId);
        var mentions = new ArrayList<Mention>();
        for (var mention : message.path("mentions")) {
            String id = ChatInputValidation.optional(mention.path("id"), "open_id");
            if (id != null) {
                mentions.add(new Mention(id, mention.path("name").asText(""), mention.path("mentioned_type").asText("unknown"),
                    ChatInputValidation.optional(mention, "key")));
            }
        }
        var resources = new ArrayList<Resource>();
        String text = switch (messageType) {
            case "text" -> content.path("text").asText("");
            case "post" -> post(content, resources);
            case "image", "file", "audio", "media" -> {
                resource(content, messageType, resources);
                yield "";
            }
            default -> "";
        };
        ChatInputValidation.size(text, resources.size());
        var metadata = json.createObjectNode().put("supported", Set.of("text", "post", "image", "file", "audio", "media").contains(messageType));
        metadata.put("rootId", message.path("root_id").asText(null));
        metadata.put("senderUnionId", ChatInputValidation.optional(sender.path("sender_id"), "union_id"));
        return new NormalizedChatEvent(providerCode(), type, kind, eventId.isBlank() ? messageId : eventId,
            messageId, chatId, chatKind.equals("p2p") ? "direct" : "group", senderKind, "feishu_open_id",
            senderId, text, messageType, mentions, resources, ChatInputValidation.optional(message, "parent_id"),
            ChatInputValidation.optional(message, "thread_id"), ChatInputValidation.time(message.path("create_time").asText(), receivedAt), metadata);
    }

    private String post(JsonNode content, List<Resource> resources) {
        JsonNode post = content.has("content") ? content : content.path("zh_cn");
        if (post.isMissingNode() && content.isObject() && content.elements().hasNext()) {
            post = content.elements().next();
        }
        var text = new StringBuilder(post.path("title").asText(""));
        for (var line : post.path("content")) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            for (var block : line) {
                switch (block.path("tag").asText()) {
                    case "text", "a" -> text.append(block.path("text").asText(""));
                    case "at" -> text.append('@').append(block.path("user_name").asText(""));
                    case "img" -> resource(block, "image", resources);
                    case "media" -> resource(block, "media", resources);
                    default -> {
                        // 未识别的富文本节点不猜测为可执行文字。
                    }
                }
            }
        }
        return text.toString();
    }

    private void resource(JsonNode content, String type, List<Resource> resources) {
        String key = ChatInputValidation.optional(content, type.equals("image") ? "image_key" : "file_key");
        if (key != null) {
            resources.add(new Resource(key, content.path("file_name").asText(type), type,
                json.createObjectNode().put("fileKey", key).put("type", type.equals("image") ? "image" : "file")));
        }
    }

    private NormalizedChatEvent nonMessage(String type, Kind kind, String eventId, JsonNode event, Instant received) {
        String message = ChatInputValidation.optional(event, "message_id");
        String chat = ChatInputValidation.optional(event, "chat_id");
        return new NormalizedChatEvent(providerCode(), type, kind, eventId, message, chat, "group", "unknown",
            "feishu_open_id", null, "", "event", List.of(), List.of(), null, null, received,
            json.createObjectNode().put("disbanded", type.equals("im.chat.disbanded_v1")));
    }

    private void verifyScope(IntegrationApplication app, JsonNode source) {
        String appId = source.path("app_id").asText("");
        String tenant = source.path("tenant_key").asText("");
        if ((!appId.isBlank() && !appId.equals(app.externalAppId())) || (!tenant.isBlank()
            && app.externalTenantId() != null && !tenant.equals(app.externalTenantId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHAT_APPLICATION_MISMATCH", "平台消息不属于当前应用或企业。");
        }
    }
}
