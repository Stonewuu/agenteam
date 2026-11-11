package com.stonewu.agenteam.mapper.agent;

import com.stonewu.agenteam.service.i18n.Messages;

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
                    Messages.text("error.agent.agentSubagentConfigurationMapper.resolve.1"));
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
            result.add(new SubagentDefinition(DYNAMIC_ID, Messages.source("text.agent.agentSubagentConfigurationMapper.read.1"),
                Messages.source("text.agent.agentSubagentConfigurationMapper.read.2"),
                Messages.source("text.agent.agentSubagentConfigurationMapper.read.3"),
                config.path("maxSteps").asInt()));
        }
        return List.copyOf(result);
    }
}
