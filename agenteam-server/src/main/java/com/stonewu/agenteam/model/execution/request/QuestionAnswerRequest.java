package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.constraints.Size;

/** 用户可选择选项、自由输入，也可在选择后补充文字。 */
public record QuestionAnswerRequest(
    @Size(max = 40, message = "{error.execution.questionAnswerRequest.optionId.1}") String optionId,
    @Size(max = 8000, message = "{error.execution.questionAnswerRequest.text.1}") String text) {
}
