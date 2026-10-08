package com.stonewu.agenteam.model.project.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 当前会话后续提交的任务使用所选项目。
 */
public record SelectProjectInput(
    @NotBlank(message = "请选择项目。") @Size(max = 100, message = "项目编号不正确。") String projectId) {
}
