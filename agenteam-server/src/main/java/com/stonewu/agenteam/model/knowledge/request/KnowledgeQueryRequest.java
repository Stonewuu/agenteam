package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 只接受关键词和结果数量，不接受数据库查询语句。
 */
public record KnowledgeQueryRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 2, max = 500, message = "文字长度不符合要求。") String query,
    @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 8L, message = "数值超出允许范围。") Integer limit) {
}
