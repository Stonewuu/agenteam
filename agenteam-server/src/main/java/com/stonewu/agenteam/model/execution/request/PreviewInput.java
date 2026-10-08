package com.stonewu.agenteam.model.execution.request;

import com.stonewu.agenteam.model.resource.request.DraftWriteRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 未保存草稿和模型选择只影响本次预览，不修改资源或发布版本。
 */
public record PreviewInput(
    @NotNull(message = "请填写必填项。") @Valid MessageInput input,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String modelProfileId,
    @Valid DraftWriteRequest draft) {
}
