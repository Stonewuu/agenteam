package com.stonewu.agenteam.model.plugin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record PluginConfigurationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:builtin|mcp)", message = "{validation.option}") String pluginType,
    @JsonProperty(value = "builtinCode", required = true) @Size(min = 1, max = 64, message = "{validation.textLength}") String builtinCode,
    @JsonProperty(value = "transport", required = true) @Size(min = 1, max = 32, message = "{validation.textLength}") @Pattern(regexp = "(?:streamable_http|legacy_sse)", message = "{validation.option}") String transport,
    @JsonProperty(value = "endpoint", required = true) @Size(min = 1, max = 2048, message = "{validation.textLength}") String endpoint,
    @JsonProperty(value = "credentialId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String credentialId,
    @NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 120L, message = "{validation.range}") Integer timeoutSeconds,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String> enabledToolNames,
    @Size(max = 100, message = "{error.plugin.pluginConfigurationInput.implementationVersion.1}") String implementationVersion) {
}
