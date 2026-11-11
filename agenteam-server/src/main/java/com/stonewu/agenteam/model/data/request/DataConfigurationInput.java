package com.stonewu.agenteam.model.data.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record DataConfigurationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:file|mysql|http)", message = "{validation.option}") String sourceType,
    @JsonProperty(value = "credentialId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String credentialId,
    @NotNull(message = "{validation.required}") @Valid Connection connection,
    @NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 10L, message = "{validation.range}") Integer timeoutSeconds,
    @NotNull(message = "{validation.required}") @AssertTrue(message = "{error.data.dataConfigurationInput.readOnly.1}") Boolean readOnly,
    @NotNull(message = "{validation.required}") @Pattern(regexp = "manual", message = "{validation.option}") String updateMode) {
    public record Connection(
        @Size(min = 1, max = 253, message = "{validation.textLength}") String host,
        @Min(value = 1L, message = "{validation.range}") @Max(value = 65535L, message = "{validation.range}") Integer port,
        @Size(min = 1, max = 64, message = "{validation.textLength}") String database,
        @Size(min = 1, max = 2048, message = "{validation.textLength}") String endpoint,
        @Size(min = 0, max = 20, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") String> queryParameters) {
    }
}
