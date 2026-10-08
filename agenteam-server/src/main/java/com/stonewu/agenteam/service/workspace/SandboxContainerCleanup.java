package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.tool.ToolCallControl;

import java.time.Duration;

/**
 * 清理由实际执行节点完成，业务服务不必直接访问 Docker 管理接口。
 */
public interface SandboxContainerCleanup {
    void removeUserContainer(String workspaceId, Duration timeout);

    void removeForWorkspace(String scope, Duration timeout, ToolCallControl control);

    default void removeForWorkspace(String scope) {
        removeForWorkspace(scope, Duration.ofSeconds(30), null);
    }
}
