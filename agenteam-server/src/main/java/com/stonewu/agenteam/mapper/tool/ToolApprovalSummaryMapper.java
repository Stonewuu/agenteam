package com.stonewu.agenteam.mapper.tool;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 展示实际参数中的目标，无法从结构确认的外部影响不编造结论。
 */
@Component
public class ToolApprovalSummaryMapper {
    public RunApprovalView.Summary map(ExecutionToolBinding binding, JsonNode redacted) {
        String title = ToolDisplayNameMapper.label(binding);
        String target = title;
        for (String key : List.of("path", "working_directory", "target", "url", "recipient", "recipients", "to",
            "destination", "recordIds", "recordId")) {
            JsonNode value = redacted.get(key);
            if (value != null && !value.isNull()) {
                String label = switch (key) {
                    case "path" -> Messages.text("text.tool.toolApprovalSummaryMapper.map.1");
                    case "working_directory" -> Messages.text("text.tool.toolApprovalSummaryMapper.map.2");
                    case "recipient", "recipients", "to" -> Messages.text("text.tool.toolApprovalSummaryMapper.map.3");
                    case "url" -> Messages.text("text.tool.toolApprovalSummaryMapper.map.4");
                    case "recordId", "recordIds" -> Messages.text("text.tool.toolApprovalSummaryMapper.map.5");
                    default -> Messages.text("text.tool.toolApprovalSummaryMapper.map.6");
                };
                target = label + "：" + (value.isTextual() ? value.asText() : value.toString());
                break;
            }
        }
        boolean platform = binding.config().path("pluginType").asText().equals("builtin")
            && List.of("todo_management", "schedule_management")
            .contains(binding.config().path("builtinCode").asText());
        // 工具定义说明面向模型；用户确认只展示实际操作及可能产生的影响。
        String description = binding.readOnly() || WorkspaceToolDefinitions.handles(binding) ? ""
            : platform ? Messages.text("text.tool.toolApprovalSummaryMapper.map.7")
            : Messages.text("text.tool.toolApprovalSummaryMapper.map.8");
        return new RunApprovalView.Summary(abbreviate(title, 100),
            abbreviate(target, 500), description, redacted.toString(), null);
    }

    private String abbreviate(String text, int max) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(max, text.codePointCount(0, text.length()))));
    }
}
