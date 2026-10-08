package com.stonewu.agenteam.model.export.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record ToolLogExportInput(
    @NotNull(message = "请填写必填项。") @Size(min = 20, max = 40, message = "文字长度不符合要求。") String from,
    @NotNull(message = "请填写必填项。") @Size(min = 20, max = 40, message = "文字长度不符合要求。") String to,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String actorUserId,
    @Size(min = 1, max = 32, message = "文字长度不符合要求。") @Pattern(regexp = "(?:prepared|waiting_approval|running|succeeded|failed|cancelled|unknown)", message = "请选择支持的选项或格式。") String status,
    @Size(min = 1, max = 32, message = "文字长度不符合要求。") @Pattern(regexp = "(?:interactive|preview|scheduled|manual_schedule)", message = "请选择支持的选项或格式。") String source,
    @Size(min = 0, max = 100, message = "文字长度不符合要求。") String query) {
}
