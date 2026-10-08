package com.stonewu.agenteam.model.project.entity;

/**
 * 保存实际使用沙盒的父执行及子会话归属，续期失联只触发核实。
 */
public record SandboxRunUsage(String runId, long leaseVersion, String session, ProjectLocation project,
                              String jobId, String owner, long updatedAt) {
}
