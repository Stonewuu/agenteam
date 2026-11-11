package com.stonewu.agenteam.model.project.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 当前会话后续提交的任务使用所选项目。
 */
public record SelectProjectInput(
    @NotBlank(message = "{error.project.selectProjectInput.projectId.1}") @Size(max = 100, message = "{error.project.selectProjectInput.projectId.2}") String projectId) {
}
