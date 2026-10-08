package com.stonewu.agenteam.model.project.entity;

import com.stonewu.agenteam.service.workspace.SandboxExecutor;

/**
 * 同一调用编号只执行一次，命令失联时保留记录供核实。
 */
public record ProjectCommandRecord(String projectId, String commandHash, SandboxExecutor.Result result,
                                   ProjectLocation project, String callId, String containerId,
                                   String generation, String token, long deadline, long updatedAt, String status) {
}
