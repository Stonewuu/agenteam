package com.stonewu.agenteam.mapper.tool;

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
                    case "path" -> "文件";
                    case "working_directory" -> "工作目录";
                    case "recipient", "recipients", "to" -> "收件人";
                    case "url" -> "地址";
                    case "recordId", "recordIds" -> "记录";
                    default -> "目标";
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
            : platform ? "确认后将修改平台中的待办或定时任务，请核对操作内容。"
            : "此工具可能修改外部数据，请核对全部参数后再决定。";
        return new RunApprovalView.Summary(abbreviate(title, 100),
            abbreviate(target, 500), description, redacted.toString(), null);
    }

    private String abbreviate(String text, int max) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(max, text.codePointCount(0, text.length()))));
    }
}
