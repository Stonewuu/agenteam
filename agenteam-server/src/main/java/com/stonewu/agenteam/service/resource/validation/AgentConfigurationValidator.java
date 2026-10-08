package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.agent.request.AgentConfigurationInput;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.memory.MemoryContentValidation;
import com.stonewu.agenteam.service.modelprofile.AgentModelSelectionService;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体类型、默认模型能力与声明依赖分别检查，草稿不改变已经发布的配置。
 */
@Component
public class AgentConfigurationValidator implements ResourceConfigValidator {
    private final ModelProfileCatalog models;
    private final MemoryContentValidation memories;

    public AgentConfigurationValidator(ModelProfileCatalog models, MemoryContentValidation memories) {
        this.models = models;
        this.memories = memories;
    }

    @Override
    public ResourceKind kind() {
        return ResourceKind.AGENT;
    }

    @Override
    public void draft(JsonNode config) {
        InputValidation.read(config, AgentConfigurationInput.class, "config");
        validateSubagents(config);
        if (config.has("temperature") && !config.path("temperature").isNumber()) {
            throw ApiException.invalidField("config.temperature", "温度必须填写数值，未配置时请移除该字段。");
        }
        for (var topic : config.path("memoryFields")) {
            if (!memories.topic(topic.asText(), "config.memoryFields").equals(topic.asText())) {
                throw ApiException.invalidField("config.memoryFields", "偏好主题前后不能留空格。");
            }
        }
        if (config.path("businessRole").asText().isBlank()) {
            throw ApiException.invalidField("config.businessRole", "请填写数字员工的业务职责。");
        }
        if (config.path("agentType").asText().equals("workflow")) {
            if (config.hasNonNull("reasoningEffort")) {
                throw ApiException.invalidField("config.reasoningEffort", "流程型员工不单独设置思考等级。");
            }
            if (!config.path("modelProfileId").isNull() || config.has("temperature")) {
                throw ApiException.invalidField("config.modelProfileId",
                    "流程型员工不单独配置模型和温度，请确认清除这些设置。");
            }
            var entry = config.path("entryWorkflowVersionId");
            if (entry.isTextual() && !contains(config.path("workflowVersionIds"), entry.asText())) {
                throw ApiException.invalidField("config.entryWorkflowVersionId", "入口必须是已选择的工作流版本。");
            }
        } else {
            if (!config.path("modelProfileId").isTextual()) {
                throw ApiException.invalidField("config.modelProfileId", "请选择默认模型。");
            }
            if (!config.path("entryWorkflowVersionId").isNull()) {
                throw ApiException.invalidField("config.entryWorkflowVersionId",
                    "对话型和任务型员工不能设置入口工作流。");
            }
            if (config.path("instructions").asText().isBlank()) {
                throw ApiException.invalidField("config.instructions", "请填写执行指令。");
            }
        }
    }

    @Override
    public void validatePublished(JsonNode config) {
        draft(config);
        if (config.path("agentType").asText().equals("workflow") && !config.path("entryWorkflowVersionId")
            .isTextual()) {
            throw ApiException.invalidField("config.entryWorkflowVersionId", "请选择入口工作流。");
        }
    }

    @Override
    public void use(AuthContext actor, JsonNode config) {
        if (config.path("agentType").asText().equals("workflow")) {
            return;
        }
        var model = models.requireAvailable(actor.enterpriseId(), config.path("modelProfileId").asText());
        var capabilities = model.capabilities();
        if (config.has("temperature") && !capabilities.supportsTemperature()) {
            unsupported("所选模型不支持温度设置，请移除此参数。");
        }
        String reason = AgentModelSelectionService.unavailableReason(config, capabilities);
        if (reason != null) {
            unsupported(reason + "，请选择其他模型。");
        }
        AgentModelSelectionService.validateReasoning(capabilities, config.path("reasoningEffort").asText(null));
    }

    @Override
    public List<DependencyBinding> dependencies(JsonNode config) {
        var result = new ArrayList<DependencyBinding>();
        DependencyFields.array(result, config, "skillVersionIds", "skill");
        DependencyFields.array(result, config, "pluginVersionIds", "plugin");
        DependencyFields.array(result, config, "knowledgeVersionIds", "knowledge");
        DependencyFields.array(result, config, "dataVersionIds", "data");
        DependencyFields.array(result, config, "workflowVersionIds", "workflow");
        DependencyFields.array(result, config, "subagentVersionIds", "agent");
        DependencyFields.add(result, config.path("entryWorkflowVersionId"), "workflow", "entryWorkflowVersionId", 0);
        return List.copyOf(result);
    }

    private boolean contains(JsonNode values, String id) {
        for (JsonNode value : values) {
            if (id.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private void validateSubagents(JsonNode config) {
        if (config.has("subagentVersionIds") && !config.path("subagentVersionIds").isArray()) {
            throw ApiException.invalidField("config.subagentVersionIds", "请选择智能体版本。");
        }
        for (var id : config.path("subagentVersionIds")) {
            if (!id.isTextual() || id.asText().isBlank() || !id.asText().equals(id.asText().strip())) {
                throw ApiException.invalidField("config.subagentVersionIds", "请选择有效的智能体版本。");
            }
        }
        if (config.has("dynamicSubagentEnabled") && !config.path("dynamicSubagentEnabled").isBoolean()) {
            throw ApiException.invalidField("config.dynamicSubagentEnabled", "请选择是否允许临时助手。");
        }
        if (config.path("agentType").asText().equals("workflow") && (!config.path("subagentVersionIds")
            .isEmpty() || config.path("dynamicSubagentEnabled").asBoolean())) {
            throw ApiException.invalidField("config.subagentVersionIds", "流程型员工请在工作流的智能体节点中配置助手。");
        }
    }

    private void unsupported(String message) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_FEATURE_UNSUPPORTED", message);
    }
}
