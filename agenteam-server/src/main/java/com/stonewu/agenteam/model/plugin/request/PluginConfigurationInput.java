package com.stonewu.agenteam.model.plugin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record PluginConfigurationInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:builtin|mcp)", message = "请选择支持的选项或格式。") String pluginType,
    @JsonProperty(value = "builtinCode", required = true) @Size(min = 1, max = 64, message = "文字长度不符合要求。") String builtinCode,
    @JsonProperty(value = "transport", required = true) @Size(min = 1, max = 32, message = "文字长度不符合要求。") @Pattern(regexp = "(?:streamable_http|legacy_sse)", message = "请选择支持的选项或格式。") String transport,
    @JsonProperty(value = "endpoint", required = true) @Size(min = 1, max = 2048, message = "文字长度不符合要求。") String endpoint,
    @JsonProperty(value = "credentialId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String credentialId,
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 120L, message = "数值超出允许范围。") Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 100, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String> enabledToolNames,
    @Size(max = 100, message = "实现版本过长。") String implementationVersion) {
}
