package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.agent.entity.SubagentDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 将已验证的发布版本和临时子智能体开关转换为可调用助手。
 */
public final class AgentSubagentConfigurationMapper {

    public static final String DYNAMIC_ID = "general-purpose";

    private AgentSubagentConfigurationMapper() {
    }

    public record ResolvedSubagent(SubagentDefinition definition, String resourceId, JsonNode config) {

        public String id() {
            return definition.id();
        }

        public Object historyKey() {
            return resourceId == null ? definition : List.of(definition, config);
        }
    }

    /**
     * 只从本次执行保存的固定依赖读取配置，不查询最新版本或雇佣关系。
     */
    public static List<ResolvedSubagent> resolve(JsonNode config, JsonNode dependencies) {
        var result = new ArrayList<ResolvedSubagent>();
        for (var id : config.path("subagentVersionIds")) {
            JsonNode selected = null;
            for (var item : dependencies) {
                if ("agent".equals(item.path("kind").asText()) && id.asText().equals(item.path("versionId").asText())) {
                    selected = item;
                    break;
                }
            }
            if (selected == null || !Set.of("chat", "task")
                .contains(selected.path("config").path("agentType").asText()) || !selected.path("config")
                .path("workflowVersionIds").isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                    "所选子智能体版本无法使用，请重新选择。");
            }
            var child = selected.path("config");
            var definition = new SubagentDefinition(referenceId(id.asText()), selected.path("name").asText(),
                child.path("businessRole").asText(), child.path("instructions").asText(),
                child.path("maxSteps").asInt());
            result.add(new ResolvedSubagent(definition, selected.path("resourceId").asText(), child));
        }
        for (var dynamic : read(config)) {
            result.add(new ResolvedSubagent(dynamic, null, config));
        }
        return List.copyOf(result);
    }

    public static String referenceId(String versionId) {
        return UUID.nameUUIDFromBytes(versionId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static List<SubagentDefinition> read(JsonNode config) {
        if ("workflow".equals(config.path("agentType").asText())) {
            return List.of();
        }
        var result = new ArrayList<SubagentDefinition>();
        if (config.path("dynamicSubagentEnabled").asBoolean()) {
            result.add(new SubagentDefinition(DYNAMIC_ID, "临时助手",
                "根据当前任务临时承担一种职责。调用时通过 label 指定助手名称，在 task 中明确职责、工作要求、必要资料和预期结果。",
                "根据父任务在本次任务说明中指定的职责与要求工作，返回清晰的成果和必要依据。只处理分配的任务，不创建子智能体。",
                config.path("maxSteps").asInt()));
        }
        return List.copyOf(result);
    }
}
