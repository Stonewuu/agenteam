package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CollaborationResultResendRequest(
        @NotBlank(message = "请重新打开发送窗口。") @Pattern(regexp = "[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", message = "请重新打开发送窗口。") String requestId,
        @NotNull(message = "请重新打开任务后发送。") @Positive(message = "请重新打开任务后发送。") Long expectedRevision,
        @NotNull(message = "请重新确认回复方式。") @PositiveOrZero(message = "请重新确认回复方式。") Long expectedReplyModeRevision) {
}
