package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.tool.ToolCallControl;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 本地和独立执行服务共用文件传入、结果保存与取消约定。
 */
public interface SandboxExecutor {
    record Result(int exitCode, boolean timedOut, boolean capacityExceeded, String stdoutPath, String stderrPath) {
    }

    Result execute(Path source, Path destination, Path privateDirectory, String callId, String command,
                   String workingDirectory, Duration timeout, ToolCallControl control);
}
