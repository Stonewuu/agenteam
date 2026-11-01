package com.stonewu.agenteam.service.chat.provider.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 完整答案使用Markdown文本；会话列表按需显示，并兼容已经提交的旧卡片。 */
public final class WecomMessageCards {
    private WecomMessageCards() {
    }

    public static ObjectNode body(JsonNode payload) {
        var body = JsonNodeFactory.instance.objectNode().put("chatid", payload.path("receiveId").asText());
        int chatType = payload.path("chatType").asInt();
        if (chatType == 1 || chatType == 2) {
            body.put("chat_type", chatType);
        }
        if ("file".equals(payload.path("messageType").asText())) {
            body.put("msgtype", "file").putObject("file").put("media_id", payload.path("content").path("media_id").asText());
            return body;
        }
        String text = payload.path("content").path("text").asText();
        String settings = payload.path("replySettingsUrl").asText();
        if (payload.hasNonNull("userQuestion") && payload.hasNonNull("questionCardId")) {
            body.put("msgtype", "template_card");
            var card = body.putObject("template_card").put("card_type", "button_interaction")
                .put("task_id", payload.path("questionCardId").asText());
            boolean direct = payload.path("questionDirect").asBoolean();
            boolean hasOptions = !payload.path("userQuestion").path("options").isEmpty();
            card.putObject("main_title").put("title", hasOptions ? "选择回答" : "填写回答")
                .put("desc", hasOptions ? "完整选项见上方，也可自己输入" : direct ? "直接发送你的回答即可" : "直接 @ 我发送你的回答即可");
            var buttons = card.putArray("button_list");
            int index = 0;
            for (var option : payload.path("userQuestion").path("options")) {
                buttons.addObject().put("text", "选 " + ++index)
                    .put("style", option.path("recommended").asBoolean() ? 1 : 2).put("key", "question_" + option.path("id").asText());
            }
            buttons.addObject().put("text", "自己输入").put("style", 2).put("key", "question_free_text");
        } else if (payload.path("historyChoices").isArray() && payload.hasNonNull("conversationCardId")) {
            body.put("msgtype", "template_card");
            var card = body.putObject("template_card").put("card_type", "button_interaction")
                .put("task_id", payload.path("conversationCardId").asText());
            card.putObject("main_title").put("title", "最近会话").put("desc", "选择后继续提问");
            if (payload.path("historyListLayout").asBoolean()) {
                var titles = new StringBuilder();
                for (var choice : payload.path("historyChoices")) {
                    if (!titles.isEmpty()) {
                        titles.append('\n');
                    }
                    titles.append(choice.path("label").asText().replace("继续 ", "")).append(". ").append(choice.path("title").asText());
                }
                card.put("sub_title_text", titles.toString());
            }
            var buttons = card.putArray("button_list");
            for (var choice : payload.path("historyChoices")) {
                buttons.addObject().put("text", choice.path("label").asText()).put("style", 2).put("key", choice.path("key").asText());
            }
            if (payload.path("historyPage").asInt() > 1) {
                buttons.addObject().put("text", "上一页").put("style", 2).put("key", "history_previous");
            }
            if (payload.path("historyHasNext").asBoolean()) {
                buttons.addObject().put("text", "下一页").put("style", 2).put("key", "history_next");
            }
        } else if (!payload.path("replyResultUrl").asText().isBlank() && payload.hasNonNull("conversationCardId")) {
            body.put("msgtype", "template_card");
            var card = body.putObject("template_card").put("card_type", "button_interaction")
                .put("task_id", payload.path("conversationCardId").asText()).put("sub_title_text", text);
            var title = card.putObject("main_title").put("title", payload.path("replyNoticeTitle").asText("回复"));
            if (!payload.path("replyFull").asBoolean()) {
                title.put("desc", "点击卡片查看内容");
            }
            card.putObject("card_action").put("type", 1).put("url", payload.path("replyResultUrl").asText());
            var buttons = card.putArray("button_list");
            buttons.addObject().put("text", "新会话").put("style", 2).put("key", "conversation_new");
            buttons.addObject().put("text", "继续该会话").put("style", 2).put("key", "conversation_resume");
        } else if (!settings.isBlank() && "text".equals(payload.path("messageType").asText())) {
            body.put("msgtype", "template_card");
            var card = body.putObject("template_card").put("card_type", "text_notice");
            if (payload.hasNonNull("conversationCardId")) {
                card.put("card_type", "button_interaction").put("task_id", payload.path("conversationCardId").asText());
                var buttons = card.putArray("button_list");
                buttons.addObject().put("text", "新对话").put("style", 2).put("key", "conversation_new");
                buttons.addObject().put("text", "继续此对话").put("style", 2).put("key", "conversation_resume");
            }
            card.putObject("main_title").put("title", payload.path("replyNoticeTitle").asText("回复方式"));
            card.put("sub_title_text", text);
            card.putArray("horizontal_content_list").addObject().put("keyname", "回复方式")
                .put("value", payload.path("replyFull").asBoolean() ? "直接回复内容" : "仅发送链接");
            card.putArray("jump_list").addObject().put("type", 1).put("title", "修改回复方式").put("url", settings);
            card.putObject("card_action").put("type", 1).put("url", settings);
        } else {
            body.put("msgtype", "markdown").putObject("markdown").put("content", text);
        }
        return body;
    }
}
