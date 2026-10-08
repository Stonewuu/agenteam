package com.stonewu.agenteam.model.project.entity;

/**
 * 持久记录容器实例与停止状态，旧请求不能操作下一次启动的实例。
 */
public record UserSandboxState(ProjectLocation project, String containerId, String generation, String daemonId,
                               String status, long idleSince, long updatedAt) {
}
