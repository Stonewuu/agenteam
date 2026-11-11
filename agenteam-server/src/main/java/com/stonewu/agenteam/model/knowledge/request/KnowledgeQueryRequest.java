package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 只接受关键词和结果数量，不接受数据库查询语句。
 */
public record KnowledgeQueryRequest(
    @NotNull(message = "{validation.required}") @Size(min = 2, max = 500, message = "{validation.textLength}") String query,
    @Min(value = 1L, message = "{validation.range}") @Max(value = 8L, message = "{validation.range}") Integer limit) {
}
