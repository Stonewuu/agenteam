package com.stonewu.agenteam.model.project.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 目录是用户工作空间中的相对位置，省略时由系统创建。
 */
public record CreateProjectInput(
    @NotBlank(message = "请填写项目名称。") @Size(max = 100, message = "项目名称不能超过 100 个字符。") String name,
    @Size(max = 256, message = "项目目录不能超过 256 个字符。") String directory) {
}
