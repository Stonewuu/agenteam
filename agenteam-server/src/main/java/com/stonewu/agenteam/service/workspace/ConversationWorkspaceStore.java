package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.project.ProjectWorkspaceStore;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 正式会话使用共享项目，智能体预览仍使用独立临时文件。
 */
@Component
public class ConversationWorkspaceStore {
    private final WorkspaceStore temporary;
    private final ProjectWorkspaceStore projects;

    public ConversationWorkspaceStore(WorkspaceStore temporary, ProjectWorkspaceStore projects) {
        this.temporary = temporary;
        this.projects = projects;
    }

    public WorkspaceSession open(RunRecord run, String session, ToolCallControl control, Duration timeout,
                                 Runnable requireLease) {
        return run.mode().equals("preview") ? temporary.open(run, session, control, timeout, requireLease)
            : projects.open(run, session, control, timeout, requireLease);
    }
}
