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
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:file|mysql|http)", message = "请选择支持的选项或格式。") String sourceType,
    @JsonProperty(value = "credentialId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String credentialId,
    @NotNull(message = "请填写必填项。") @Valid Connection connection,
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 10L, message = "数值超出允许范围。") Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") @AssertTrue(message = "该选项不符合要求。") Boolean readOnly,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "manual", message = "请选择支持的选项或格式。") String updateMode) {
    public record Connection(
        @Size(min = 1, max = 253, message = "文字长度不符合要求。") String host,
        @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 65535L, message = "数值超出允许范围。") Integer port,
        @Size(min = 1, max = 64, message = "文字长度不符合要求。") String database,
        @Size(min = 1, max = 2048, message = "文字长度不符合要求。") String endpoint,
        @Size(min = 0, max = 20, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String> queryParameters) {
    }
}
