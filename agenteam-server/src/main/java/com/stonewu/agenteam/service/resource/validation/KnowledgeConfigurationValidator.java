package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.knowledge.request.KnowledgeConfigurationInput;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.InputValidation;
import org.springframework.stereotype.Component;

/**
 * 知识库结构限制关键词检索及真实读取上限；文档可用性在实际检索时检查。
 */
@Component
public class KnowledgeConfigurationValidator implements ResourceConfigValidator {
    @Override
    public ResourceKind kind() {
        return ResourceKind.KNOWLEDGE;
    }

    @Override
    public void draft(JsonNode config) {
        InputValidation.read(config, KnowledgeConfigurationInput.class, "config");
    }
}
