package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 所有交互对话共享的问题工具；执行身份与渠道由服务端提供。 */
@Component
public class UserQuestionTool {
    public static final String NAME = "ask_user";
    private final ResourceJson json;
    private final ToolDefinition definition;

    public UserQuestionTool(ResourceJson json) {
        this.json = json;
        var option = Map.of("type", "object", "properties", Map.of("label", text(120),
            "description", Map.of("type", "string", "maxLength", 300), "recommended", Map.of("type", "boolean")),
            "required", List.of("label"), "additionalProperties", false);
        var schema = json.tree(Map.of("type", "object", "properties", Map.of("question", text(2000),
            "options", Map.of("type", "array", "items", option, "maxItems", 4)),
            "required", List.of("question"), "additionalProperties", false));
        definition = new ToolDefinition(NAME,
            "向当前用户提问并等待回答，可提供至多四个选项及说明，最多标记一个推荐选项。用户始终可以自由输入。仅在确实需要用户补充信息或作出选择时使用，不能代替业务操作审批，也不要重复询问已知信息。工具返回的是用户回答，之后继续处理原任务。",
            json.hash(schema), schema, null, json.tree(Map.of("title", "询问用户", "readOnlyHint", true)),
            "read", false, false, false, List.of(), 30);
    }

    public Map<String, ExecutionToolBinding> list(RunRecord run) {
        if (!"interactive".equals(run.mode())) {
            return Map.of();
        }
        String kind = run.executionConfig().has("workflowId") ? "workflow" : "agent";
        String resource = run.executionConfig().path(kind + "Id").asText();
        var binding = new ExecutionToolBinding(resource, run.agentVersionId(), kind, "对话", null, definition,
            json.tree(Map.of("userQuestionTool", true)));
        return Map.of(binding.alias(), binding);
    }

    public static boolean handles(ExecutionToolBinding binding) {
        return NAME.equals(binding.definition().name()) && Set.of("agent", "workflow").contains(binding.resourceKind())
            && binding.config().path("userQuestionTool").asBoolean();
    }

    public static boolean matches(RunRecord run, String alias) {
        String kind = run.executionConfig().has("workflowId") ? "workflow" : "agent";
        return ExecutionToolBinding.alias(NAME, null, run.agentVersionId(), run.executionConfig().path(kind + "Id").asText()).equals(alias);
    }

    public static void validate(JsonNode input) {
        if (input.path("question").asText().isBlank()) {
            throw invalid("请填写要向用户提出的问题。");
        }
        int recommended = 0;
        var labels = new HashSet<String>();
        for (var option : input.path("options")) {
            String label = option.path("label").asText().strip();
            if (label.isBlank() || !labels.add(label)) {
                throw invalid("选项名称不能为空或重复。");
            }
            recommended += option.path("recommended").asBoolean() ? 1 : 0;
        }
        if (recommended > 1) {
            throw invalid("最多标记一个推荐选项。");
        }
    }

    private static Map<String, Object> text(int length) {
        return Map.of("type", "string", "minLength", 1, "maxLength", length);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENTS_INVALID", message);
    }
}
