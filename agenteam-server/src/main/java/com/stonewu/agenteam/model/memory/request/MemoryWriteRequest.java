package com.stonewu.agenteam.model.memory.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MemoryWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 50, message = "文字长度不符合要求。") String memoryKey,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 500, message = "文字长度不符合要求。") String content,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String sourceMessageId) {
}
