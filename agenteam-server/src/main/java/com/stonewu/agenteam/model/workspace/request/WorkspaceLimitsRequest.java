package com.stonewu.agenteam.model.workspace.request;

import jakarta.validation.constraints.Min;

/** 系统管理员提交的工作空间容量设置。 */
public record WorkspaceLimitsRequest(@Min(16) int workspaceMb,
                                     @Min(1) int fileMb,
                                     @Min(10) int maximumFiles,
                                     @Min(1) int commandResultsMb) {
}
