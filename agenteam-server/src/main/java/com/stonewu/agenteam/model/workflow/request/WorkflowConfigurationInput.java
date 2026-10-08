package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record WorkflowConfigurationInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
    @NotNull(message = "请填写必填项。") @Size(min = 2, max = 50, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") JsonNode> nodes,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid EdgesItem> edges) {
    public record EdgesItem(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String edgeId,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String source,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String target,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:default|true|false|approve|reject)", message = "请选择支持的选项或格式。") String branch) {
    }
}
