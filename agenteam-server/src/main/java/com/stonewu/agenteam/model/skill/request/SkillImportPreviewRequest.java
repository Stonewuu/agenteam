package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 预览只接受当前用户已经上传并通过检查的文件。
 */
public record SkillImportPreviewRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String fileId) {
}
