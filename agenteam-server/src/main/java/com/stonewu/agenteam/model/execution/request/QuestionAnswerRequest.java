package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.constraints.Size;

/** 用户可选择选项、自由输入，也可在选择后补充文字。 */
public record QuestionAnswerRequest(
    @Size(max = 40, message = "请选择本次问题提供的选项。") String optionId,
    @Size(max = 8000, message = "回答不能超过八千个字符。") String text) {
}
