package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用本人已检查的新文件更新同一份知识文档。
 */
public record DocumentReplaceRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String fileId) {
}
