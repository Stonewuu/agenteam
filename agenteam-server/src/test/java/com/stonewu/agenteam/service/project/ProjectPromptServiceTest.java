package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ProjectPromptServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectMetadataService projects = mock(ProjectMetadataService.class);
    private final ConversationProjectService conversations = mock(ConversationProjectService.class);
    private final ProjectPromptService prompts = new ProjectPromptService(projects, conversations, json);

    @Test
    void freezesProjectContextAndKeepsNamesAsData() {
        var config = json.createObjectNode().put("workspaceProjectId", "first").put("workspaceProjectName", "财务\n\"报告\"")
            .put("workspaceProjectDirectory", "projects/财务 报告");
        String prompt = prompts.instructions(run(config, "interactive"));
        assertTrue(prompt.contains("财务\\n\\\"报告\\\""));
        assertTrue(prompt.contains("/workspace/projects/财务 报告"));
        assertTrue(prompt.contains("未经用户明确同意，不得读取、列出、搜索、修改或删除当前项目之外的用户内容"));
        assertTrue(prompt.contains("符号链接、脚本或子智能体间接访问"));
        verifyNoInteractions(projects, conversations);
        config.put("workspaceProjectName", "第二项目").put("workspaceProjectDirectory", "projects/second");
        String changed = prompts.instructions(run(config, "interactive"));
        assertTrue(changed.contains("/workspace/projects/second"));
        assertFalse(changed.contains("/workspace/projects/财务 报告"));
    }

    @Test
    void legacyQueuedRunsUseTheirSavedProjectAndPreviewsRemainTemporary() {
        var saved = new WorkspaceProjectRow();
        saved.setId("previous");
        saved.setName("提交时的项目");
        saved.setDirectoryPath("projects/previous");
        when(projects.require("enterprise", "user", "previous")).thenReturn(saved);
        String prompt = prompts.instructions(run(json.createObjectNode().put("workspaceProjectId", "previous"), "interactive"));
        assertTrue(prompt.contains("/workspace/projects/previous"));
        verifyNoInteractions(conversations);
        assertTrue(prompts.instructions(run(json.createObjectNode(), "preview")).contains("/workspace/work"));
    }

    private RunRecord run(ObjectNode config, String mode) {
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.mode()).thenReturn(mode);
        when(run.executionConfig()).thenReturn(config);
        return run;
    }
}
