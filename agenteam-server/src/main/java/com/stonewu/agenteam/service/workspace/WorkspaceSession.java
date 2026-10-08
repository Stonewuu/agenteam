package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.tool.ToolCallControl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * 文件操作共用的会话接口，持久项目和临时预览分别管理自己的文件生命周期。
 */
public interface WorkspaceSession extends AutoCloseable {
    WorkspaceStore.Manifest manifest();

    Path directory();

    boolean cleared();

    Duration remaining();

    WorkspaceFilesystem current();

    WorkspaceFilesystem prepare();

    void commit(Set<String> sources, Set<String> inputs);

    default boolean persistent() {
        return false;
    }

    default SandboxExecutor.Result execute(String callId, String command, String workingDirectory, Duration timeout,
                                           ToolCallControl control) {
        throw new UnsupportedOperationException("临时工作目录应使用独立沙箱执行");
    }

    @Override
    void close() throws IOException;
}
