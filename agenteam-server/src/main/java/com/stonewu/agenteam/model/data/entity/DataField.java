package com.stonewu.agenteam.model.data.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * 字段类型与当前读取、筛选、排序限制共同决定查询是否允许。
 */
public record DataField(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String label,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:string|integer|decimal|boolean|date|datetime|object)", message = "{validation.option}") String valueType,
    @JsonProperty(value = "readable", required = true) boolean readable,
    @JsonProperty(value = "filterable", required = true) boolean filterable,
    @JsonProperty(value = "sortable", required = true) boolean sortable,
    @JsonProperty(value = "sensitive", required = true) boolean sensitive,
    @JsonProperty(value = "nullable", required = true) boolean nullable,
    @JsonProperty(value = "ordinal", required = true) @Min(value = 0L, message = "{validation.range}") @Max(value = 99L, message = "{validation.range}") int ordinal) {
}
