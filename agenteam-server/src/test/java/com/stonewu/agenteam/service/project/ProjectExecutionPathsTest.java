package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectExecutionPathsTest {
    @TempDir
    Path root;

    @Test
    void resolvesProjectAndUserSpaceDirectoriesWithoutExposingHostPaths() throws Exception {
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
        var project = new ProjectLocation(ProjectPaths.workspaceId("enterprise", "user"), "report", "enterprise", "user", "projects/财务 报告");
        layout.prepareWorkspace(project.workspaceId(), false);
        Files.createDirectories(layout.project(project).resolve("work"));
        assertEquals(layout.project(project), ProjectExecutionPaths.workingDirectory(layout, project, null));
        assertEquals(layout.project(project).resolve("work"), ProjectExecutionPaths.workingDirectory(layout, project, "work"));
        assertEquals(layout.project(project), ProjectExecutionPaths.workingDirectory(layout, project, "/workspace/projects/财务 报告"));
        assertEquals(layout.files(project.workspaceId()), ProjectExecutionPaths.workingDirectory(layout, project, "../.."));
        assertEquals(layout.files(project.workspaceId()).resolve("projects/其他项目"),
            ProjectExecutionPaths.workingDirectory(layout, project, "../其他项目"));
        for (String invalid : List.of("../../..", "/workspace/../control", "/workspace//etc", "/tmp", "D:/data", "work\\file")) {
            assertThrows(ApiException.class, () -> ProjectExecutionPaths.workingDirectory(layout, project, invalid), invalid);
        }
    }
}
