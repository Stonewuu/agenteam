package com.stonewu.agenteam.model.data.request;

import com.stonewu.agenteam.model.data.entity.DataField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 确认预览及字段定义后，完整验证每一行才能提交新版本。
 */
public record DataImportConfirmRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 8192, message = "文字长度不符合要求。") String previewToken,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid DataField> fields) {
}
