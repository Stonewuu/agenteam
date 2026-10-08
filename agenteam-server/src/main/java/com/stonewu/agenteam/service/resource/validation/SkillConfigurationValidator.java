package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.skill.request.SkillConfigurationInput;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能保存指令与明确声明的插件、知识依赖，不能成为任意执行代码的入口。
 */
@Component
public class SkillConfigurationValidator implements ResourceConfigValidator {
    @Override
    public ResourceKind kind() {
        return ResourceKind.SKILL;
    }

    @Override
    public void draft(JsonNode config) {
        InputValidation.read(config, SkillConfigurationInput.class, "config");
        if (config.path("instructions").asText().isBlank()) {
            throw ApiException.invalidField("config.instructions", "请填写技能执行指令。");
        }
    }

    @Override
    public List<DependencyBinding> dependencies(JsonNode config) {
        var result = new ArrayList<DependencyBinding>();
        DependencyFields.array(result, config, "pluginVersionIds", "plugin");
        DependencyFields.array(result, config, "knowledgeVersionIds", "knowledge");
        return List.copyOf(result);
    }
}
