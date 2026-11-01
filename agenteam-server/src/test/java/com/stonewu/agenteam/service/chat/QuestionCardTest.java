package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuMessageCards;
import com.stonewu.agenteam.service.chat.provider.wecom.WecomMessageCards;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class QuestionCardTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void wecomKeepsCompleteLongQuestionInTextAndOnlyShortLabelsInCard() {
        var question = question();
        String text = ChatQuestionMessages.text(question, true);
        assertTrue(text.contains(question.question()));
        assertTrue(text.contains(question.options().getFirst().label()));
        assertTrue(text.contains(question.options().getFirst().description()));
        assertTrue(text.contains("直接 @ 我输入回答"));
        var parts = ChatTextParts.split(text);
        assertEquals(text, String.join("", parts));
        assertTrue(parts.stream().allMatch(part -> part.getBytes(StandardCharsets.UTF_8).length <= 3500));
        var payload = json.createObjectNode().put("questionCardId", "source").put("receiveId", "group").put("chatType", 2);
        payload.set("userQuestion", json.valueToTree(question));
        var card = WecomMessageCards.body(payload).path("template_card");
        assertEquals("source", card.path("task_id").asText());
        assertEquals("button_interaction", card.path("card_type").asText());
        assertEquals("选 1", card.at("/button_list/0/text").asText());
        assertEquals("question_option_0", card.at("/button_list/0/key").asText());
        assertEquals("question_free_text", card.at("/button_list/1/key").asText());
        assertFalse(card.toString().contains(question.question()));
    }

    @Test
    void feishuIncludesFullQuestionAndRequiredFreeTextFormWithoutDefaultSelection() {
        var question = question();
        var payload = json.createObjectNode().put("messageType", "text");
        payload.set("userQuestion", json.valueToTree(question));
        var result = FeishuMessageCards.prepare(payload);
        assertEquals("interactive", result.path("messageType").asText());
        assertEquals(question.question(), result.at("/content/body/elements/0/content").asText());
        var elements = result.at("/content/body/elements");
        var form = StreamSupport.stream(elements.spliterator(), false).filter(element -> "form".equals(element.path("tag").asText())).findFirst().orElseThrow();
        assertEquals("form", form.path("tag").asText());
        assertEquals("answer", form.at("/elements/0/name").asText());
        assertTrue(form.at("/elements/0/required").asBoolean());
        assertEquals(1000, form.at("/elements/0/max_length").asInt());
        assertEquals("multiline_text", form.at("/elements/0/input_type").asText());
        assertTrue(form.at("/elements/0/auto_resize").asBoolean());
        assertEquals(6, form.at("/elements/0/max_rows").asInt());
        assertFalse(form.at("/elements/0").has("default_value"));
        assertEquals("submit", form.at("/elements/1/form_action_type").asText());
        assertEquals("question_submit", form.at("/elements/1/behaviors/0/value/action").asText());
        assertTrue(elements.toString().contains("question_free_text"));
        assertFalse(elements.toString().contains("\"lines\""));
    }

    @Test
    void maximumQuestionOptionsAndUnicodeStayCompleteWithinPlatformMessageSizes() throws Exception {
        var options = maximumOptions();
        var question = new RunQuestionView("question", "run", "问😀".repeat(1000), options, "pending", null, null, "1", "2026-11-03T12:00:00Z");
        String text = ChatQuestionMessages.text(question, true);
        var parts = ChatTextParts.split(text);
        assertTrue(parts.size() > 1);
        assertEquals(text, String.join("", parts));
        for (var part : parts) {
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= 3500);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
        var payload = json.createObjectNode().put("messageType", "text").put("questionCardId", "source");
        payload.set("userQuestion", json.valueToTree(question));
        var feishu = FeishuMessageCards.prepare(payload).path("content");
        assertTrue(json.writeValueAsBytes(feishu).length < 30000);
        for (var option : options) {
            assertTrue(feishu.toString().contains(option.label()));
            assertTrue(feishu.toString().contains(option.description()));
        }
        var wecom = WecomMessageCards.body(payload).path("template_card");
        assertEquals(5, wecom.path("button_list").size());
        wecom.path("button_list").forEach(button -> assertTrue(button.path("text").asText().length() <= 4));
        assertFalse(wecom.toString().contains(question.question()));
    }

    @Test
    void feishuPreservesSymbolsAsTextAndNeverCreatesMentionsOrCollapsedOptionLabels() {
        var question = new RunQuestionView("question", "run", "<at id=all>所有人</at> & [原文] *保持符号*", List.of(
            new RunQuestionView.Option("option_0", "<隐藏> _下划线_", "# 标题 [链接](https://example.com)", false)), "pending", null, null, "1", "2026-11-03T12:00:00Z");
        var payload = json.createObjectNode().put("messageType", "text");
        payload.set("userQuestion", json.valueToTree(question));
        var result = FeishuMessageCards.prepare(payload).path("content");
        assertEquals("&#60;at id=all&#62;所有人&#60;/at&#62; &#38; &#91;原文&#93; &#42;保持符号&#42;", result.at("/body/elements/0/content").asText());
        assertFalse(result.toString().contains("<at"));
        assertTrue(result.toString().contains("&#60;隐藏&#62; &#95;下划线&#95;"));
        var maximum = new RunQuestionView("q", "r", "~".repeat(2000), List.of(0, 1, 2, 3).stream()
            .map(index -> new RunQuestionView.Option("option_" + index, "~".repeat(120), "~".repeat(300), index == 0)).toList(),
            "pending", null, null, "1", "2026-11-03T12:00:00Z");
        payload.set("userQuestion", json.valueToTree(maximum));
        assertTrue(FeishuMessageCards.prepare(payload).path("content").toString().getBytes(StandardCharsets.UTF_8).length < 30000);
    }

    @Test
    void questionsWithoutChoicesHaveNoSelectionButtonsOrInstructionToChooseAnOption() {
        var question = new RunQuestionView("q", "r", "你的目标是什么？", List.of(), "pending", null, null, "1", "2026-11-03T12:00:00Z");
        var payload = json.createObjectNode().put("messageType", "text").put("questionCardId", "card").put("questionDirect", true);
        payload.set("userQuestion", json.valueToTree(question));
        var card = WecomMessageCards.body(payload).path("template_card");
        assertEquals(1, card.path("button_list").size());
        assertEquals("自己输入", card.at("/button_list/0/text").asText());
        assertEquals("直接发送你的回答即可", card.at("/main_title/desc").asText());
        assertFalse(FeishuMessageCards.prepare(payload).path("content").toString().contains("question_option_"));
        assertFalse(FeishuMessageCards.prepare(payload).path("content").toString().contains("@ 我"));
    }

    private List<RunQuestionView.Option> maximumOptions() {
        return List.of(0, 1, 2, 3).stream().map(index -> new RunQuestionView.Option("option_" + index,
            "方案".repeat(60), "具体说明".repeat(75), index == 0)).toList();
    }

    private RunQuestionView question() {
        return new RunQuestionView("question", "run", "请说明报告的用途。".repeat(90), List.of(
            new RunQuestionView.Option("option_0", "先生成适合团队负责人阅读的简短摘要", "保留结论与需要处理的问题。".repeat(10), true)),
            "pending", null, null, "1", "2026-10-31T12:00:00Z");
    }
}
