package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 预览只接受当前用户已经上传并通过检查的文件。
 */
public record SkillImportPreviewRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String fileId) {
}
