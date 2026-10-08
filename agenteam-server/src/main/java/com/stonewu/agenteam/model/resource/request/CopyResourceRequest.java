package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 复制时使用的新名称；配置和可引用依赖由服务端读取。
 */
public record CopyResourceRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name) {
}
