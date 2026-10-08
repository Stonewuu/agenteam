package com.stonewu.agenteam.mapper.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将本轮明确选择的固定说明按顺序加入模型输入，不修改员工的系统指令。
 */
@Component
public class SkillPromptMapper {
    public String append(String input, List<JsonNode> skills) {
        if (skills.isEmpty()) {
            return input;
        }
        var prompt = new StringBuilder(input).append("\n\n本次任务选择的技能，请按以下顺序应用：\n");
        int order = 0;
        for (var skill : skills) {
            prompt.append("\n技能 ").append(++order).append("：").append(skill.path("name").asText()).append('\n');
            JsonNode config = skill.path("config");
            append(prompt, "适用场景", config.path("scenario").asText());
            append(prompt, "输入说明", config.path("inputDescription").asText());
            append(prompt, "执行指令", config.path("instructions").asText());
            append(prompt, "输出说明", config.path("outputDescription").asText());
            append(prompt, "示例", config.path("example").asText());
        }
        return prompt.toString();
    }

    private void append(StringBuilder prompt, String label, String text) {
        if (!text.isBlank()) {
            prompt.append(label).append("：\n").append(text).append('\n');
        }
    }
}
