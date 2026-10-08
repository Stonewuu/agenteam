package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.UserWorkspaceRow;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectWorkspaceRevisionTest {
    @TempDir
    Path root;

    @Test
    void invalidatesBothProjectVersionsForOneOwnerAndPreservesTheirFiles() throws Exception {
        var workspace = new UserWorkspaceRow();
        workspace.setId(ProjectPaths.workspaceId("enterprise", "alice"));
        var metadata = mock(ProjectMetadataService.class);
        var conversations = mock(ConversationProjectService.class);
        when(metadata.workspace("enterprise", "alice", workspace.getId())).thenReturn(workspace);
        for (String id : new String[]{"first", "second"}) {
            var project = new WorkspaceProjectRow();
            project.setId(id);
            project.setWorkspaceId(workspace.getId());
            project.setEnterpriseId("enterprise");
            project.setUserId("alice");
            project.setDirectoryPath("projects/" + id);
            when(conversations.ensure("enterprise", "alice", id)).thenReturn(project);
        }
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
        var migration = mock(LegacyProjectMigration.class);
        when(migration.copy(any(), isNull(), any(), any(), any())).thenReturn(new LegacyProjectMigration.Origins(Set.of(), Set.of()));
        var settings = new WorkspaceSettings(root.toString(), "python:3.13-slim", false, 16, 1, 200, 256, 1, 32, 25, "none");
        var store = new ProjectWorkspaceStore(metadata, conversations, layout, migration, mock(ProjectCommandExecutor.class), settings,
            mock(ProjectInputFileService.class));
        String first = version(store, "first");
        String second = version(store, "second");
        var firstLocation = new ProjectLocation(workspace.getId(), "first", "enterprise", "alice", "projects/first");
        var file = layout.project(firstLocation).resolve("keep.txt");
        Files.writeString(file, "项目文件保留");
        String savedManifest = layout.read(layout.state(firstLocation), WorkspaceStore.Manifest.class).version();
        assertEquals(first, version(store, "first"));
        layout.recordFileChange(workspace.getId());
        assertNotEquals(first, version(store, "first"));
        assertNotEquals(second, version(store, "second"));
        assertEquals(savedManifest, layout.read(layout.state(firstLocation), WorkspaceStore.Manifest.class).version());
        assertEquals("项目文件保留", Files.readString(file));
        String after = version(store, "first");
        String otherOwner = ProjectPaths.workspaceId("enterprise", "bob");
        layout.prepareWorkspace(otherOwner, false);
        layout.recordFileChange(otherOwner);
        assertEquals(after, version(store, "first"));
    }

    private String version(ProjectWorkspaceStore store, String conversation) {
        return store.read("enterprise", "alice", conversation, snapshot -> snapshot.manifest().version());
    }
}
