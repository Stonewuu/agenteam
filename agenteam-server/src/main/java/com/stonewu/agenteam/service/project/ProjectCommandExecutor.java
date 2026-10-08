package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;

import java.time.Duration;

/**
 * 命令直接操作项目持久目录，不返回一份用于覆盖项目的文件树。
 */
public interface ProjectCommandExecutor {
    SandboxExecutor.Result execute(ProjectLocation location, String callId, String command, String workingDirectory,
                                   Duration timeout, ToolCallControl control);

    void recover(ProjectLocation location, Duration timeout, ToolCallControl control);
}
