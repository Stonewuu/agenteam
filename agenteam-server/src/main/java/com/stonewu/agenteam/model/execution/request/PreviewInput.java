package com.stonewu.agenteam.model.execution.request;

import com.stonewu.agenteam.model.resource.request.DraftWriteRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 未保存草稿和模型选择只影响本次预览，不修改资源或发布版本。
 */
public record PreviewInput(
    @NotNull(message = "{validation.required}") @Valid MessageInput input,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String modelProfileId,
    @Valid DraftWriteRequest draft) {
}
