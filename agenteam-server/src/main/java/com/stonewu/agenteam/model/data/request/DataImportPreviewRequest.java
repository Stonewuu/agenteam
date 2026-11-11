package com.stonewu.agenteam.model.data.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新文件必须明确指定集合，不能根据同名记录推断替换目标。
 */
public record DataImportPreviewRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String fileId,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String collectionId) {
}
