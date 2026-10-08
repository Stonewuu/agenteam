package com.stonewu.agenteam.model.data.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 结构化数据查询固定集合、版本、字段和有限比较条件。
 */
public record DataQueryRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String collectionId,
    @JsonProperty(value = "generation", required = true) @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 1000000000L, message = "数值超出允许范围。") int generation,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String> fields,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid Filter> filters,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 3, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid Sort> sort,
    @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 200L, message = "数值超出允许范围。") Integer limit,
    @Min(value = 0L, message = "数值超出允许范围。") @Max(value = 100000L, message = "数值超出允许范围。") Integer offset) {
    public record Filter(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String field,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:eq|ne|gt|gte|lt|lte|in|contains|is_null)", message = "请选择支持的选项或格式。") String operator,
        Object value) {
    }

    public record Sort(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String field,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 4, message = "文字长度不符合要求。") @Pattern(regexp = "(?:asc|desc)", message = "请选择支持的选项或格式。") String direction) {
    }
}
