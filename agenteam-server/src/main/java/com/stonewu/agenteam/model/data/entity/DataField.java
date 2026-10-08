package com.stonewu.agenteam.model.data.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * 字段类型与当前读取、筛选、排序限制共同决定查询是否允许。
 */
public record DataField(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String label,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:string|integer|decimal|boolean|date|datetime|object)", message = "请选择支持的选项或格式。") String valueType,
    @JsonProperty(value = "readable", required = true) boolean readable,
    @JsonProperty(value = "filterable", required = true) boolean filterable,
    @JsonProperty(value = "sortable", required = true) boolean sortable,
    @JsonProperty(value = "sensitive", required = true) boolean sensitive,
    @JsonProperty(value = "nullable", required = true) boolean nullable,
    @JsonProperty(value = "ordinal", required = true) @Min(value = 0L, message = "数值超出允许范围。") @Max(value = 99L, message = "数值超出允许范围。") int ordinal) {
}
