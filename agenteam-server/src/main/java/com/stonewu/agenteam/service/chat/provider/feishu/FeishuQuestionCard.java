package com.stonewu.agenteam.service.chat.provider.feishu;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 飞书可直接提交选项，也可在同一张卡片填写自己的回答。 */
public final class FeishuQuestionCard {
    private FeishuQuestionCard() {
    }

    public static void elements(ArrayNode elements, JsonNode question, boolean direct) {
        markdown(elements, safe(question.path("question").asText())).put("text_size", "heading");
        if (!question.path("options").isEmpty()) {
            elements.addObject().put("tag", "hr");
        }
        int index = 0;
        for (var option : question.path("options")) {
            String title = ++index + ". " + safe(option.path("label").asText()) + (option.path("recommended").asBoolean() ? Messages.text("text.chat.feishuQuestionCard.elements.1") : "");
            String description = option.path("description").asText();
            markdown(elements, "**" + title + "**" + (description.isBlank() ? "" : "\n" + safe(description)));
            button(elements, Messages.text("text.chat.feishuQuestionCard.elements.2", index), option.path("recommended").asBoolean() ? "primary" : "default", "question_" + option.path("id").asText());
        }
        elements.addObject().put("tag", "hr");
        var controls = elements.addObject().put("tag", "form").put("name", "question_answer").putArray("elements");
        var input = controls.addObject().put("tag", "input").put("name", "answer").put("input_type", "multiline_text")
            .put("required", true).put("width", "fill").put("max_length", 1000).put("rows", 2)
            .put("auto_resize", true).put("max_rows", 6).put("label_position", "top");
        input.putObject("label").put("tag", "plain_text").put("content", Messages.text("text.chat.feishuQuestionCard.elements.3"));
        input.putObject("placeholder").put("tag", "plain_text").put("content", Messages.text("text.chat.feishuQuestionCard.elements.4"));
        button(controls, Messages.text("text.chat.feishuQuestionCard.elements.5"), "primary", "question_submit").put("name", "submit_answer").put("form_action_type", "submit");
        button(elements, Messages.text("text.chat.feishuQuestionCard.elements.6"), "default", "question_free_text");
        markdown(elements, direct ? Messages.text("text.chat.feishuQuestionCard.elements.7")
            : Messages.text("text.chat.feishuQuestionCard.elements.8")).put("text_size", "notation");
    }

    private static ObjectNode button(ArrayNode elements, String label, String type, String action) {
        var button = elements.addObject().put("tag", "button").put("type", type);
        button.putObject("text").put("tag", "plain_text").put("content", label);
        button.putArray("behaviors").addObject().put("type", "callback").putObject("value").put("action", action);
        return button;
    }

    private static String safe(String text) {
        var result = new StringBuilder();
        // 使用平台支持的字符转义，选项中的符号不会变成提及、链接或隐藏格式。
        text.codePoints().forEach(point -> {
            if ("&<>\\*_`[]#~!".indexOf(point) >= 0) {
                result.append("&#").append(point).append(';');
            } else {
                result.appendCodePoint(point);
            }
        });
        return result.toString();
    }

    private static ObjectNode markdown(ArrayNode elements, String text) {
        return elements.addObject().put("tag", "markdown").put("content", text);
    }
}
