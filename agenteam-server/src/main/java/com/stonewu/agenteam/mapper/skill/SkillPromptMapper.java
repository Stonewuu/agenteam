package com.stonewu.agenteam.mapper.skill;

import com.stonewu.agenteam.service.i18n.Messages;

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
        var prompt = new StringBuilder(input).append(Messages.source("text.skill.skillPromptMapper.append.1"));
        int order = 0;
        for (var skill : skills) {
            prompt.append(Messages.source("text.skill.skillPromptMapper.append.2")).append(++order).append("：").append(skill.path("name").asText()).append('\n');
            JsonNode config = skill.path("config");
            append(prompt, Messages.source("text.skill.skillPromptMapper.append.3"), config.path("scenario").asText());
            append(prompt, Messages.source("text.skill.skillPromptMapper.append.4"), config.path("inputDescription").asText());
            append(prompt, Messages.source("text.skill.skillPromptMapper.append.5"), config.path("instructions").asText());
            append(prompt, Messages.source("text.skill.skillPromptMapper.append.6"), config.path("outputDescription").asText());
            append(prompt, Messages.source("text.skill.skillPromptMapper.append.7"), config.path("example").asText());
        }
        return prompt.toString();
    }

    private void append(StringBuilder prompt, String label, String text) {
        if (!text.isBlank()) {
            prompt.append(label).append("：\n").append(text).append('\n');
        }
    }
}
