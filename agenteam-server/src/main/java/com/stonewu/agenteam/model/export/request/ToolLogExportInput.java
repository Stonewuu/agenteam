package com.stonewu.agenteam.model.export.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record ToolLogExportInput(
    @NotNull(message = "{validation.required}") @Size(min = 20, max = 40, message = "{validation.textLength}") String from,
    @NotNull(message = "{validation.required}") @Size(min = 20, max = 40, message = "{validation.textLength}") String to,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String actorUserId,
    @Size(min = 1, max = 32, message = "{validation.textLength}") @Pattern(regexp = "(?:prepared|waiting_approval|waiting_input|running|succeeded|failed|cancelled|unknown)", message = "{validation.option}") String status,
    @Size(min = 1, max = 32, message = "{validation.textLength}") @Pattern(regexp = "(?:interactive|preview|scheduled|manual_schedule)", message = "{validation.option}") String source,
    @Size(min = 0, max = 100, message = "{validation.textLength}") String query) {
}
