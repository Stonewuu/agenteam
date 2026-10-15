package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 取消和重试请求使用原记录版本，重试时必须明确具体工作。 */
public record CollaborationActionRequest(@NotBlank(message = "请填写请求编号。")
        @Pattern(regexp = "[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", message = "请求编号格式不正确。") String requestId,
        @NotNull(message = "请提供当前版本。") @Positive(message = "版本必须为正整数。") Long expectedRevision,
        @Size(max = 100, message = "工作编号过长。") String workItemId, @Size(max = 2000, message = "说明过长，请缩短后重试。") String reason) {
}
