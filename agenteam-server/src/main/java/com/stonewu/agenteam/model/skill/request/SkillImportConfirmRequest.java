package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 确认内容允许编辑，引用仍须属于本企业并通过当前授权。
 */
public record SkillImportConfirmRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 8192, message = "{validation.textLength}") String previewToken,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 500, message = "{validation.textLength}") String description,
    @NotNull(message = "{validation.required}") Map<String, Object> config) {
}
