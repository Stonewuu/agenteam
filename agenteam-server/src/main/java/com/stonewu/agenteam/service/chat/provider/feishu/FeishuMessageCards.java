package com.stonewu.agenteam.service.chat.provider.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** 飞书正文统一使用 Markdown 卡片，设置入口只放在任务入口消息中。 */
public final class FeishuMessageCards {
    private FeishuMessageCards() {
    }

    public static JsonNode prepare(JsonNode source) {
        String type = source.path("messageType").asText("text");
        if (!"text".equals(type) && !"interactive_update".equals(type)) {
            return source;
        }
        ObjectNode payload = source.deepCopy();
        ObjectNode card = JsonNodeFactory.instance.objectNode().put("schema", "2.0");
        card.putObject("config").put("update_multi", true);
        var elements = card.putObject("body").putArray("elements");
        // 不让模型正文中的平台提及标签通知整群或其他成员。
        String markdown = source.path("content").path("text").asText().replaceAll("(?is)<at\\b[^>]*>(.*?)</at>", "@$1");
        elements.addObject().put("tag", "markdown").put("content", markdown.isBlank() ? " " : markdown);
        String settings = source.path("replySettingsUrl").asText();
        if (!settings.isBlank()) {
            elements.addObject().put("tag", "hr");
            elements.addObject().put("tag", "markdown").put("content", "回复方式：" + (source.path("replyFull").asBoolean() ? "直接回复内容" : "仅发送链接"));
            if (source.path("replyCallbackEnabled").asBoolean() && source.path("replySettingsEditing").asBoolean()) {
                form(elements, source);
            } else {
                var button = button(elements, "回复方式", "default");
                var behavior = button.putArray("behaviors").addObject();
                if (source.path("replyCallbackEnabled").asBoolean()) {
                    behavior.put("type", "callback").putObject("value").put("action", "reply_mode_open");
                } else {
                    behavior.put("type", "open_url").put("default_url", settings);
                }
            }
        }
        if (source.path("replyCardProbe").asBoolean()) {
            button(elements, "确认卡片按钮", "primary").putArray("behaviors").addObject()
                .put("type", "callback").putObject("value").put("action", "reply_mode_probe");
        }
        payload.put("messageType", "interactive_update".equals(type) ? type : "interactive").set("content", card);
        return payload;
    }

    private static void form(ArrayNode elements, JsonNode source) {
        boolean allowed = source.path("replyFullAllowed").asBoolean();
        if (!allowed) {
            elements.addObject().put("tag", "markdown").put("content", "本群已设为仅发送链接。");
        }
        var controls = elements.addObject().put("tag", "form").put("name", "reply_mode_form").putArray("elements");
        var defaultChoice = controls.addObject().put("tag", "checker").put("name", "saveDefault").put("checked", false);
        defaultChoice.putObject("text").put("tag", "plain_text").put("content", source.path("replyDirect").asBoolean()
            ? "设为我在此私聊的默认方式" : "设为我在本群的默认方式");
        for (String mode : new String[]{"chat_full", "summary_link"}) {
            boolean full = "chat_full".equals(mode);
            boolean selected = source.path("replyFull").asBoolean() == full;
            var choice = button(controls, (full ? "直接回复内容" : "仅发送链接") + (selected ? " · 当前" : ""), selected ? "primary" : "default")
                .put("name", mode).put("action_type", "form_submit").put("disabled", full && !allowed);
            if (full && !allowed) {
                choice.putObject("disabled_tips").put("tag", "plain_text").put("content", "本群已设为仅发送链接");
            }
            choice.putArray("behaviors").addObject().put("type", "callback").putObject("value")
                .put("action", "reply_mode_save").put("outputVisibility", mode)
                .put("expectedRevision", source.path("replyModeRevision").asLong())
                .put("expectedDefaultRevision", source.path("replyDefaultRevision").asLong());
        }
        button(controls, "收起", "default").put("name", "close").put("action_type", "form_submit")
            .putArray("behaviors").addObject().put("type", "callback").putObject("value").put("action", "reply_mode_close");
    }

    private static ObjectNode button(ArrayNode elements, String text, String type) {
        var button = elements.addObject().put("tag", "button").put("type", type);
        button.putObject("text").put("tag", "plain_text").put("content", text);
        return button;
    }
}
