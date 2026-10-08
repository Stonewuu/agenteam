package com.stonewu.agenteam.service.modelprofile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一处理智能体默认值、对话选择及模型能力校验，不修改智能体发布版本。
 */
@Service
public class AgentModelSelectionService {
    private final ModelProfileCatalog models;

    public AgentModelSelectionService(ModelProfileCatalog models) {
        this.models = models;
    }

    public ObjectNode apply(AuthContext actor, JsonNode original, ModelSelection selection) {
        ObjectNode config = original.deepCopy();
        if (selection == null) {
            return config;
        }
        InputValidation.validate(selection);
        if ("workflow".equals(config.path("agentType").asText())) {
            throw ApiException.invalidField("modelSelection", "流程型员工不能在对话中切换模型。");
        }
        var model = models.requireAvailable(actor.enterpriseId(), selection.modelProfileId());
        if (!selection.modelProfileId().equals(config.path("modelProfileId").asText())
            && !model.capabilities().supportsTemperature()) {
            config.remove("temperature");
        }
        config.put("modelProfileId", selection.modelProfileId());
        if (selection.reasoningEffort() == null) {
            config.remove("reasoningEffort");
        } else {
            config.put("reasoningEffort", selection.reasoningEffort());
        }
        validateReasoning(model.capabilities(), selection.reasoningEffort());
        return config;
    }

    public static String unavailableReason(JsonNode config, ModelCapabilities capabilities) {
        if (!capabilities.inputTypes().contains("text")) {
            return "不支持文字输入";
        }
        boolean tools = List.of("pluginVersionIds", "knowledgeVersionIds", "dataVersionIds", "workflowVersionIds",
                "subagentVersionIds")
            .stream().anyMatch(field -> !config.path(field).isEmpty());
        tools = tools || config.path("dynamicSubagentEnabled").asBoolean() || !AgentSubagentConfigurationMapper.read(
            config).isEmpty();
        if (tools && !capabilities.supportsTools()) {
            return "不支持此员工所需的工具调用";
        }
        return null;
    }

    public static void validateReasoning(ModelCapabilities capabilities, String effort) {
        if (effort != null && !capabilities.reasoningEfforts().contains(effort)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_REASONING_UNSUPPORTED",
                "当前模型不支持所选思考等级，请重新选择。");
        }
    }
}
