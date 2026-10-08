package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.UserWorkspaceRow;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectWorkspaceRecoveryTest {
    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();
    private final UserWorkspaceRow workspace = new UserWorkspaceRow();
    private ProjectWorkspaceLayout layout;
    private ProjectWorkspaceStore store;
    private WorkspaceProjectRow fresh;

    @BeforeEach
    void prepare() throws Exception {
        workspace.setId(ProjectPaths.workspaceId("enterprise", "user"));
        workspace.setEnterpriseId("enterprise");
        workspace.setUserId("user");
        var metadata = mock(ProjectMetadataService.class);
        var conversations = mock(ConversationProjectService.class);
        when(metadata.workspace("enterprise", "user", workspace.getId())).thenReturn(workspace);
        for (String id : new String[]{"existing", "fresh"}) {
            var project = new WorkspaceProjectRow();
            project.setId(id);
            project.setWorkspaceId(workspace.getId());
            project.setEnterpriseId("enterprise");
            project.setUserId("user");
            project.setDirectoryPath("projects/" + id);
            project.setLegacyConversationId(id);
            when(conversations.ensure("enterprise", "user", id)).thenReturn(project);
            when(metadata.require("enterprise", "user", id)).thenReturn(project);
            if (id.equals("fresh")) {
                fresh = project;
            }
        }
        doAnswer(invocation -> {
            UserWorkspaceRow owner = invocation.getArgument(0);
            WorkspaceProjectRow project = invocation.getArgument(1);
            owner.setInitializedAt(Instant.EPOCH);
            project.setInitializedAt(Instant.EPOCH);
            return null;
        }).when(metadata).initialized(any(), any());
        layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.resolve("storage").toString(), ""), json);
        var migration = mock(LegacyProjectMigration.class);
        when(migration.copy(any(), any(), any(), any(), any())).thenReturn(new LegacyProjectMigration.Origins(Set.of(), Set.of()));
        var settings = new WorkspaceSettings(root.toString(), "python:3.13-slim", false, 16, 1, 200, 256, 1, 32, 25, "none");
        store = new ProjectWorkspaceStore(metadata, conversations, layout, migration, mock(ProjectCommandExecutor.class), settings,
            mock(ProjectInputFileService.class));
        store.read("enterprise", "user", "existing", WorkspaceStore.Snapshot::manifest);
        Files.writeString(layout.project(location("existing")).resolve("outputs/keep.txt"), "原项目文件");
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "workspace", "files", "control"})
    void newProjectCanCreateFilesAfterDirectoriesAreDeletedWhileOldProjectRemainsUnavailable(String removedPart) throws Exception {
        assertNotNull(workspace.getInitializedAt());
        Path removed = switch (removedPart) {
            case "root" -> layout.root();
            case "workspace" -> layout.workspace(workspace.getId());
            case "files" -> layout.files(workspace.getId());
            default -> layout.control(workspace.getId());
        };
        // 只删除本测试临时目录内的数据，不接触本机的实际项目文件。
        WorkspaceStore.deleteTree(root, removed);
        assertExistingUnavailable();

        var manifest = store.read("enterprise", "user", "fresh", WorkspaceStore.Snapshot::manifest);
        assertEquals("fresh", manifest.conversationId());
        assertNotNull(fresh.getInitializedAt());
        writeFreshProjectFile();
        assertEquals("新项目可以创建文件", Files.readString(layout.project(location("fresh")).resolve("outputs/new.txt")));
        assertEquals(location("fresh"), layout.read(layout.registration(location("fresh")), ProjectLocation.class));
        assertExistingUnavailable();
        if (removedPart.equals("control")) {
            assertEquals("原项目文件", Files.readString(layout.project(location("existing")).resolve("outputs/keep.txt")));
            assertFalse(Files.exists(layout.registration(location("existing"))));
        } else {
            assertFalse(Files.exists(layout.project(location("existing"))));
        }
    }

    @Test
    void newProjectPreservesExistingProjectWhenStorageIsAvailable() throws Exception {
        writeFreshProjectFile();
        assertEquals("原项目文件", Files.readString(layout.project(location("existing")).resolve("outputs/keep.txt")));
        assertTrue(store.read("enterprise", "user", "existing", snapshot -> snapshot.files().files("outputs", false))
            .stream().anyMatch(file -> file.path().equals("outputs/keep.txt")));
    }

    private void writeFreshProjectFile() throws Exception {
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn("fresh");
        when(run.executionConfig()).thenReturn(json.createObjectNode().put("workspaceProjectId", "fresh"));
        try (var control = new ToolCallControl(() -> {
        }); var session = store.open(run, "fresh", control, Duration.ofSeconds(5), () -> {
        })) {
            session.prepare().write(null, "outputs/new.txt", "新项目可以创建文件");
            session.commit(Set.of(), Set.of());
        }
    }

    private void assertExistingUnavailable() {
        var failure = assertThrows(ApiException.class,
            () -> store.read("enterprise", "user", "existing", WorkspaceStore.Snapshot::manifest));
        assertEquals("PROJECT_STORAGE_UNAVAILABLE", failure.code());
    }

    private ProjectLocation location(String project) {
        return new ProjectLocation(workspace.getId(), project, "enterprise", "user", "projects/" + project);
    }
}
