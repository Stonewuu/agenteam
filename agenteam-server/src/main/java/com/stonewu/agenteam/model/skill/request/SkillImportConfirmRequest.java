package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 确认内容允许编辑，引用仍须属于本企业并通过当前授权。
 */
public record SkillImportConfirmRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 8192, message = "文字长度不符合要求。") String previewToken,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 500, message = "文字长度不符合要求。") String description,
    @NotNull(message = "请填写必填项。") Map<String, Object> config) {
}
