package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.stereotype.Component;

/**
 * 每次构造智能体时提供本次执行固定的项目，历史对话不能改变当前目录。
 */
@Component
public class ProjectPromptService {
    private final ProjectMetadataService projects;
    private final ConversationProjectService conversations;
    private final ObjectMapper json;

    public ProjectPromptService(ProjectMetadataService projects, ConversationProjectService conversations,
                                ObjectMapper json) {
        this.projects = projects;
        this.conversations = conversations;
        this.json = json;
    }

    public String instructions(RunRecord run) {
        if (run.mode().equals("preview")) {
            return Messages.source("text.project.projectPromptService.instructions.1");
        }
        var config = run.executionConfig();
        String id = config.path("workspaceProjectId").asText(null);
        String name = config.path("workspaceProjectName").asText(null);
        String directory = config.path("workspaceProjectDirectory").asText(null);
        // 升级前排队的任务没有目录快照，仍按已保存的项目编号读取，不能误用后来切换的项目。
        if (id == null || name == null || directory == null) {
            var project = id == null ? conversations.ensure(run.enterpriseId(), run.userId(), run.conversationId())
                : projects.require(run.enterpriseId(), run.userId(), id);
            id = project.getId();
            name = project.getName();
            directory = project.getDirectoryPath();
        }
        directory = ProjectPaths.directory(directory, id);
        var context = json.createObjectNode().put(Messages.source("text.project.projectPromptService.instructions.2"), id).put(Messages.source("text.project.projectPromptService.instructions.3"), name)
            .put(Messages.source("text.project.projectPromptService.instructions.4"), ProjectExecutionPaths.ROOT)
            .put(Messages.source("text.project.projectPromptService.instructions.5"), ProjectExecutionPaths.projectDirectory(directory));
        return Messages.source("text.project.projectPromptService.instructions.6", context);
    }
}
