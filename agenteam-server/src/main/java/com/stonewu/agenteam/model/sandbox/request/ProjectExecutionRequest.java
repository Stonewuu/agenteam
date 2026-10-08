package com.stonewu.agenteam.model.sandbox.request;

import com.stonewu.agenteam.model.project.entity.ProjectLocation;

/**
 * 项目执行引用共享存储上的登记信息，不传输或替换整份项目目录。
 */
public record ProjectExecutionRequest(ProjectLocation project, String callId, String command, String workingDirectory,
                                      long timeoutMillis, String networkPolicy, boolean recoverOnly) {
}
