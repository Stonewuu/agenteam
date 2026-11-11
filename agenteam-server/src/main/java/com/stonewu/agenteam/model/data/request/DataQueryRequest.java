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
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String collectionId,
    @JsonProperty(value = "generation", required = true) @Min(value = 1L, message = "{validation.range}") @Max(value = 1000000000L, message = "{validation.range}") int generation,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") @UniqueElements(message = "{error.agent.agentConfigurationInput.skillVersionIds.1}") List<@NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String> fields,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 20, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid Filter> filters,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 3, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid Sort> sort,
    @Min(value = 1L, message = "{validation.range}") @Max(value = 200L, message = "{validation.range}") Integer limit,
    @Min(value = 0L, message = "{validation.range}") @Max(value = 100000L, message = "{validation.range}") Integer offset) {
    public record Filter(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String field,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:eq|ne|gt|gte|lt|lte|in|contains|is_null)", message = "{validation.option}") String operator,
        Object value) {
    }

    public record Sort(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String field,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 4, message = "{validation.textLength}") @Pattern(regexp = "(?:asc|desc)", message = "{validation.option}") String direction) {
    }
}
