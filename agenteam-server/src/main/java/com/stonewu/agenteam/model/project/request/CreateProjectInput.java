package com.stonewu.agenteam.model.project.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 目录是用户工作空间中的相对位置，省略时由系统创建。
 */
public record CreateProjectInput(
    @NotBlank(message = "{error.project.createProjectInput.name.1}") @Size(max = 100, message = "{error.project.createProjectInput.name.2}") String name,
    @Size(max = 256, message = "{error.project.createProjectInput.directory.1}") String directory) {
}
