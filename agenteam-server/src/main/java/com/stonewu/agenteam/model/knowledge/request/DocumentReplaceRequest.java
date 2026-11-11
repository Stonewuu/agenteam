package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用本人已检查的新文件更新同一份知识文档。
 */
public record DocumentReplaceRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String fileId) {
}
