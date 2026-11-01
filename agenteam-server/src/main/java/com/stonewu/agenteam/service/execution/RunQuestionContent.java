package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.request.QuestionAnswerRequest;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/** 问题选项编号由服务端生成，答案只允许使用本次选项或用户自己输入的文字。 */
@Component
public class RunQuestionContent {
    private final ObjectMapper json;
    private final PayloadEncryption encryption;

    public RunQuestionContent(ObjectMapper json, PayloadEncryption encryption) {
        this.json = json;
        this.encryption = encryption;
    }

    public JsonNode question(JsonNode input) {
        var value = json.createObjectNode().put("question", input.path("question").asText().strip());
        var options = value.putArray("options");
        for (var option : input.path("options")) {
            String id = "option_" + options.size();
            options.addObject().put("id", id).put("label", option.path("label").asText().strip())
                .put("description", option.path("description").asText("").strip())
                .put("recommended", option.path("recommended").asBoolean());
        }
        return value;
    }

    public JsonNode answer(RunQuestionRow row, QuestionAnswerRequest input) {
        String optionId = input.optionId() == null ? "" : input.optionId().strip();
        String text = input.text() == null ? "" : input.text().strip();
        if (optionId.isEmpty() && text.isEmpty()) {
            throw ApiException.invalidField("text", "请选择一个选项，或输入你的回答。");
        }
        if (text.codePointCount(0, text.length()) > 8000) {
            throw ApiException.invalidField("text", "回答不能超过八千个字符。");
        }
        String label = "";
        for (var option : read(row, false).path("options")) {
            if (optionId.equals(option.path("id").asText())) {
                label = option.path("label").asText();
            }
        }
        if (!optionId.isEmpty() && label.isEmpty()) {
            throw ApiException.invalidField("optionId", "请选择本次问题提供的选项。");
        }
        String answer = label.isEmpty() ? text : text.isEmpty() ? label : label + "\n" + text;
        return json.createObjectNode().put("answer", answer).put("selectedOptionId", optionId).put("customText", text);
    }

    public String encrypt(RunQuestionRow row, boolean answer, JsonNode value) {
        try {
            return json.writeValueAsString(encryption.encrypt(value, binding(row, answer)));
        } catch (Exception failure) {
            throw new IllegalStateException("问题内容无法加密保存", failure);
        }
    }

    public JsonNode read(RunQuestionRow row, boolean answer) {
        String value = answer ? row.getAnswerEncrypted() : row.getQuestionEncrypted();
        if (value == null) {
            return json.createObjectNode();
        }
        try {
            return encryption.decrypt(json.readValue(value, EncryptedPayload.class), binding(row, answer), JsonNode.class);
        } catch (Exception failure) {
            throw new IllegalStateException("已保存的问题内容无法读取", failure);
        }
    }

    public RunQuestionView view(RunQuestionRow row) {
        var question = read(row, false);
        var options = new ArrayList<RunQuestionView.Option>();
        for (var option : question.path("options")) {
            options.add(new RunQuestionView.Option(option.path("id").asText(), option.path("label").asText(),
                option.path("description").asText(), option.path("recommended").asBoolean()));
        }
        var answer = read(row, true);
        return new RunQuestionView(row.getId(), row.getRunId(), question.path("question").asText(), options,
            row.getStatus(), answer.path("answer").asText(null), answer.path("selectedOptionId").asText(null),
            row.getRevision().toString(), row.getExpiresAt().toString());
    }

    private String binding(RunQuestionRow row, boolean answer) {
        return row.getEnterpriseId() + ":run-question:" + row.getId() + (answer ? ":answer" : ":question");
    }
}
