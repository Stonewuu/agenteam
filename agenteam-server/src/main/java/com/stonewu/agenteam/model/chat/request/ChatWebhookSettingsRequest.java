package com.stonewu.agenteam.model.chat.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 校验材料只写入专用加密存储，不返回原文。 */
public record ChatWebhookSettingsRequest(@NotNull @Positive Long expectedRevision,
        @NotBlank @Size(max = 8192) String verificationToken, @NotBlank @Size(max = 8192) String encryptKey) {
}
