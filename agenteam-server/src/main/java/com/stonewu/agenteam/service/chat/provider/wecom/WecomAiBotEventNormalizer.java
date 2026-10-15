package com.stonewu.agenteam.service.chat.provider.wecom;

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

/** 企微专用机器人事件按真实协议字段识别，不能用普通自建应用编号代替机器人身份。 */
@Component
public class WecomAiBotEventNormalizer implements ChatEventNormalizer {
    private final ObjectMapper json;

    public WecomAiBotEventNormalizer(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String providerCode() {
        return "wecom_aibot";
    }

    @Override
    public NormalizedChatEvent normalize(IntegrationApplication app, String ownBotId, JsonNode frame, Instant received) {
        var body = frame.path("body");
        String receiver = body.path("aibotid").asText(body.path("botid").asText(""));
        if (!receiver.isBlank() && !receiver.equals(app.externalAppId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHAT_APPLICATION_MISMATCH", "平台消息不属于当前机器人。");
        }
        String command = frame.path("cmd").asText();
        String eventType = body.path("event").path("eventtype").asText("unknown");
        String messageId = ChatInputValidation.optional(body, "msgid");
        String requestId = frame.path("headers").path("req_id").asText(messageId == null ? "" : messageId);
        if (!command.equals("aibot_msg_callback")) {
            Kind kind = command.equals("aibot_event_callback") ? switch (eventType) {
                case "enter_chat" -> Kind.SESSION_ENTERED;
                case "feedback_event" -> Kind.FEEDBACK;
                case "template_card_event" -> Kind.CARD_ACTION;
                case "disconnected_event" -> Kind.CONNECTION_REPLACED;
                default -> Kind.IGNORED;
            } : Kind.IGNORED;
            return new NormalizedChatEvent(providerCode(), eventType, kind, requestId, messageId,
                ChatInputValidation.optional(body, "chatid"), "direct", "unknown", "wecom_aibot_userid", null,
                "", "event", List.of(), List.of(), null, null, received, json.createObjectNode());
        }
        messageId = ChatInputValidation.required(body, "msgid");
        String sender = ChatInputValidation.required(body.path("from"), "userid");
        String chatKind = body.path("chattype").asText();
        if (!Set.of("group", "single").contains(chatKind)) {
            throw ApiException.invalidField("message", "企微消息的会话类型无法识别。");
        }
        String chat = chatKind.equals("single") ? sender : ChatInputValidation.required(body, "chatid");
        String senderType = body.path("from").path("type").asText("user");
        String senderKind = switch (senderType) {
            case "user" -> "user";
            case "bot", "app" -> sender.equals(ownBotId) ? "self_bot" : "other_bot";
            default -> "unknown";
        };
        String type = body.path("msgtype").asText("unknown");
        var resources = new ArrayList<Resource>();
        String text = switch (type) {
            case "text" -> body.path("text").path("content").asText("");
            case "voice" -> body.path("voice").path("content").asText("");
            case "mixed" -> mixed(body.path("mixed"), resources);
            case "image", "file", "video" -> {
                resource(body, type, resources);
                yield "";
            }
            default -> "";
        };
        ChatInputValidation.size(text, resources.size());
        // 首期企微只开放提及单入口；不能从正文中的岗位名称推断其他被提及的机器人。
        var mentions = chatKind.equals("group") ? List.of(new Mention(ownBotId, "", "bot")) : List.<Mention>of();
        var metadata = json.createObjectNode().put("supported", Set.of("text", "voice", "mixed", "image", "file", "video").contains(type));
        return new NormalizedChatEvent(providerCode(), command, Kind.MESSAGE, messageId, messageId, chat,
            chatKind.equals("single") ? "direct" : "group", senderKind, "wecom_aibot_userid", sender, text, type,
            mentions, resources, null, null, ChatInputValidation.time(body.path("create_time").asText(), received), metadata);
    }

    private String mixed(JsonNode mixed, List<Resource> resources) {
        var text = new StringBuilder();
        for (var part : mixed.path("msg_item")) {
            String type = part.path("msgtype").asText();
            if (type.equals("text")) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part.path("text").path("content").asText(""));
            } else if (Set.of("image", "file", "video").contains(type)) {
                resource(part, type, resources);
            }
        }
        return text.toString();
    }

    private void resource(JsonNode body, String type, List<Resource> resources) {
        var descriptor = body.path(type);
        if (!descriptor.path("url").asText("").isBlank()) {
            resources.add(new Resource(null, descriptor.path("name").asText(type), type, descriptor.deepCopy()));
        }
    }
}
