package com.stonewu.agenteam.model.chat.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record ChatReplyPreferenceRequest(
        @NotNull(message = "请选择回复方式。") @Pattern(regexp = "chat_full|summary_link", message = "请选择有效的回复方式。") String outputVisibility,
        @NotNull(message = "请重新打开设置后保存。") @PositiveOrZero(message = "请重新打开设置后保存。") Long expectedRevision) {
}
