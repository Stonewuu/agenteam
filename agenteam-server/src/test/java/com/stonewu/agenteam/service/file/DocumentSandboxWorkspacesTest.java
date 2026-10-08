package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DocumentSandboxWorkspacesTest {
    @TempDir
    Path root;

    @Test
    void keepsActiveFilesAndRetriesFailedContainerCleanupWithoutLosingItsRecord() throws Exception {
        var docker = mock(DockerWorkspaceCommands.class);
        var workspaces = new DocumentSandboxWorkspaces(root, docker);
        var lease = workspaces.create();
        Path directory = lease.directory();
        Path pending = directory.resolve("sandbox.pending");
        Files.writeString(pending, "test-scope");
        Files.setLastModifiedTime(directory, FileTime.from(Instant.EPOCH));
        workspaces.cleanupUnused();
        assertTrue(Files.exists(pending));
        verifyNoInteractions(docker);

        doThrow(new IllegalStateException("模拟 Docker 暂时不可用")).when(docker).removeForWorkspace("test-scope");
        lease.close();
        assertTrue(Files.exists(pending));

        doNothing().when(docker).removeForWorkspace("test-scope");
        workspaces.cleanupUnused();
        assertFalse(Files.exists(directory));
        verify(docker, times(2)).removeForWorkspace("test-scope");
    }
}
