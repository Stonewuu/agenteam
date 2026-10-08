package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyProjectMigrationTest {
    @TempDir
    Path root;

    private WorkspaceStore store() {
        var settings = new WorkspaceSettings(root.resolve("old").toString(), "python:3.13-slim", true, 16, 1, 100, 256, 1, 32, 30, "none");
        return new WorkspaceStore(settings, new ObjectMapper());
    }

    private RunRecord run() {
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn("conversation");
        when(run.id()).thenReturn("run");
        return run;
    }

    private ProjectLocation project() {
        return new ProjectLocation(ProjectPaths.workspaceId("enterprise", "user"), "project", "enterprise", "user", "projects/project");
    }

    private void save(String session, String text) throws Exception {
        try (var control = new ToolCallControl(() -> {
        }); var scope = store().open(run(), session, control, Duration.ofSeconds(5), () -> {
        })) {
            scope.prepare().write(RuntimeContext.empty(), "outputs/report.txt", text);
            scope.commit(Set.of("result-" + session), Set.of("input-" + session));
        }
    }

    @Test
    void migratesMainAndChildFilesWithoutOverwritingAndKeepsOriginals() throws Exception {
        save("conversation", "主会话文件");
        save("child", "子会话文件");
        Path target = Files.createDirectories(root.resolve("project"));
        var migration = new LegacyProjectMigration(store(), mock(ToolCallMapper.class), new ObjectMapper(), mock(DockerWorkspaceCommands.class));
        try (var control = new ToolCallControl(() -> {
        })) {
            var origins = migration.copy(project(), "conversation", target, control, Duration.ofSeconds(5));
            assertEquals(Set.of("input-conversation", "input-child"), origins.inputs());
            assertEquals("主会话文件", Files.readString(target.resolve("outputs/report.txt")));
            assertEquals("子会话文件", Files.readString(target.resolve("work/legacy-sessions").resolve(EncryptedAgentStateStore.component("child")).resolve("outputs/report.txt")));
            assertEquals("主会话文件", new String(store().snapshot("enterprise", "user", "conversation").files().bytes("outputs/report.txt"), StandardCharsets.UTF_8));
            assertEquals(origins, migration.copy(project(), "conversation", target, control, Duration.ofSeconds(5)));
            Files.writeString(target.resolve("outputs/report.txt"), "不能被旧文件覆盖");
            assertThrows(ApiException.class, () -> migration.copy(project(), "conversation", target, control, Duration.ofSeconds(5)));
            assertEquals("不能被旧文件覆盖", Files.readString(target.resolve("outputs/report.txt")));
        }
    }

    @Test
    void refusesToTreatMissingSavedVersionAsAnEmptyProject() throws Exception {
        save("conversation", "仍存在于旧版本目录的文件");
        Files.delete(store().directory(run(), "conversation").resolve("current.json"));
        var calls = mock(ToolCallMapper.class);
        when(calls.hasConversationWorkspace("enterprise", "user", "conversation")).thenReturn(true);
        var migration = new LegacyProjectMigration(store(), calls, new ObjectMapper(), mock(DockerWorkspaceCommands.class));
        try (var control = new ToolCallControl(() -> {
        })) {
            Path target = Files.createDirectories(root.resolve("project"));
            assertEquals("PROJECT_STORAGE_UNAVAILABLE", assertThrows(ApiException.class,
                () -> migration.copy(project(), "conversation", target, control, Duration.ofSeconds(5))).code());
        }
    }

    @Test
    void preservesChildOnlyWorkspaceAndRejectsMissingStorageWithSavedRecords() throws Exception {
        save("child", "只有子会话产出");
        var calls = mock(ToolCallMapper.class);
        var migration = new LegacyProjectMigration(store(), calls, new ObjectMapper(), mock(DockerWorkspaceCommands.class));
        try (var control = new ToolCallControl(() -> {
        })) {
            Path target = Files.createDirectories(root.resolve("project"));
            var origins = migration.copy(project(), "conversation", target, control, Duration.ofSeconds(5));
            assertEquals(Set.of("input-child"), origins.inputs());
            when(calls.hasConversationWorkspace("enterprise", "user", "missing")).thenReturn(true);
            assertEquals("PROJECT_STORAGE_UNAVAILABLE", assertThrows(ApiException.class,
                () -> migration.copy(project(), "missing", target, control, Duration.ofSeconds(5))).code());
        }
    }
}
