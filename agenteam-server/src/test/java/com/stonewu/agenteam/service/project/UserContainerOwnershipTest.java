package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserContainerOwnershipTest {
    @TempDir
    Path root;

    @Test
    void namesUseDockerHostStorageInsteadOfContainerLocalPaths() {
        String id = "a".repeat(64);
        var left = new UserWorkspaceSettings(root.resolve("left").toString(), "/volumes/one");
        var same = new UserWorkspaceSettings(root.resolve("other-local-path").toString(), "/volumes/one");
        var other = new UserWorkspaceSettings(root.resolve("left").toString(), "/volumes/two");
        assertEquals(name(left, id), name(same, id));
        assertNotEquals(name(left, id), name(other, id));
    }

    @Test
    void foreignOwnershipPreventsDeletionAndStopping() {
        var settings = new UserWorkspaceSettings(root.toString(), "");
        var docker = spy(new DockerWorkspaceCommands(settings));
        doReturn(new DockerWorkspaceCommands.Result(0, "b".repeat(64) + " foreign-storage"))
            .when(docker).run(anyList(), any(Duration.class), isNull());
        String id = "a".repeat(64);
        var failure = assertThrows(ApiException.class, () -> docker.removeUserContainer(id, Duration.ofSeconds(5)));
        assertEquals("PROJECT_CONTAINER_OWNERSHIP_MISMATCH", failure.code());
        var layout = new ProjectWorkspaceLayout(settings, new ObjectMapper());
        var runtime = new UserContainerRuntime(workspaceSettings(), runtimeSettings(), layout, docker);
        assertThrows(ApiException.class, () -> runtime.stop(new ProjectLocation(id, "project", "enterprise", "user", "projects/project"), Duration.ofSeconds(5)));
        verify(docker, never()).remove(anyString(), any(Duration.class));
        verify(docker, never()).run(anyList(), any(Duration.class));
    }

    @Test
    void independentDeploymentsKeepSeparateContainersForSameUser() throws Exception {
        String workspaceId = ProjectPaths.workspaceId("same-enterprise", "same-user");
        var project = new ProjectLocation(workspaceId, "project", "same-enterprise", "same-user", "projects/project");
        var leftSettings = new UserWorkspaceSettings(root.resolve("left").toString(), "");
        var rightSettings = new UserWorkspaceSettings(root.resolve("right").toString(), "");
        var left = new ProjectWorkspaceLayout(leftSettings, new ObjectMapper());
        var right = new ProjectWorkspaceLayout(rightSettings, new ObjectMapper());
        var leftDocker = new DockerWorkspaceCommands(leftSettings);
        var rightDocker = new DockerWorkspaceCommands(rightSettings);
        prepare(left, project);
        prepare(right, project);
        try (var control = new ToolCallControl(() -> {
        })) {
            assertEquals(0, executor(left, leftDocker).execute(project, "left", "printf left > result.txt", ".", Duration.ofSeconds(25), control).exitCode());
            assertEquals(0, executor(right, rightDocker).execute(project, "right", "printf right > result.txt", ".", Duration.ofSeconds(25), control).exitCode());
            String rightId = rightDocker.require(List.of("inspect", "--format={{.Id}}", name(rightSettings, workspaceId)), Duration.ofSeconds(10), null).output().trim();
            leftDocker.removeUserContainer(workspaceId, Duration.ofSeconds(10));
            leftDocker.removeUserContainer(workspaceId, Duration.ofSeconds(10));
            assertEquals(rightId, rightDocker.require(List.of("inspect", "--format={{.Id}}", name(rightSettings, workspaceId)), Duration.ofSeconds(10), null).output().trim());
            assertEquals("left", Files.readString(left.project(project).resolve("result.txt")));
            assertEquals("right", Files.readString(right.project(project).resolve("result.txt")));
            assertFalse(Files.exists(right.control(workspaceId).resolve("container.pending")));
        } finally {
            leftDocker.removeUserContainer(workspaceId, Duration.ofSeconds(10));
            rightDocker.removeUserContainer(workspaceId, Duration.ofSeconds(10));
        }
    }

    private String name(UserWorkspaceSettings settings, String workspaceId) {
        return UserContainerOwnership.name(settings.hostPath(Path.of(settings.root()).resolve(workspaceId).resolve("files")));
    }

    @Test
    void legacyRecoveryRequiresOldContainerToBeStoppedWithoutMutatingIt() throws Exception {
        String enterprise = "legacy-" + root.getFileName();
        String workspaceId = ProjectPaths.workspaceId(enterprise, "user");
        var project = new ProjectLocation(workspaceId, "legacy", enterprise, "user", "projects/legacy");
        var settings = new UserWorkspaceSettings(root.toString(), "");
        var layout = new ProjectWorkspaceLayout(settings, new ObjectMapper());
        var docker = new DockerWorkspaceCommands(settings);
        prepare(layout, project);
        String daemon = docker.require(List.of("info", "--format={{.ID}}"), Duration.ofSeconds(10), null).output().trim();
        Path pending = layout.control(workspaceId).resolve("container.pending");
        layout.write(pending, Map.of("project", project, "daemonId", daemon));
        String containerId = docker.require(List.of("create", "--name=agenteam-user-" + workspaceId,
            "--network=none", "--read-only", "--user=65534:65534", "--cap-drop=ALL", "--pids-limit=32", "--memory=64m",
            "python:3.13-slim", "sh", "-c", "while :; do sleep 3600; done"), Duration.ofSeconds(10), null).output().trim();
        try (var control = new ToolCallControl(() -> {
        })) {
            docker.require(List.of("start", containerId));
            assertThrows(ApiException.class, () -> executor(layout, docker).recover(project, Duration.ofSeconds(10), control));
            assertTrue(Files.exists(pending));
            assertEquals("true", docker.require(List.of("inspect", "--format={{.State.Running}}", containerId), Duration.ofSeconds(10), null).output().trim());
            docker.require(List.of("stop", "--time=0", containerId));
            executor(layout, docker).recover(project, Duration.ofSeconds(10), control);
            assertFalse(Files.exists(pending));
            assertEquals("false", docker.require(List.of("inspect", "--format={{.State.Running}}", containerId), Duration.ofSeconds(10), null).output().trim());
        } finally {
            docker.remove(containerId);
        }
    }

    private WorkspaceSettings workspaceSettings() {
        return new WorkspaceSettings(root.toString(), "python:3.13-slim", true, 16, 1, 200, 256, 1, 32, 30, "none");
    }

    private SandboxRuntimeSettings runtimeSettings() {
        return new SandboxRuntimeSettings(32, 2, "", "");
    }

    private DockerProjectCommandExecutor executor(ProjectWorkspaceLayout layout, DockerWorkspaceCommands docker) {
        var settings = workspaceSettings();
        var runtime = runtimeSettings();
        return new DockerProjectCommandExecutor(settings, runtime, layout, new UserContainerRuntime(settings, runtime, layout, docker), docker);
    }

    private void prepare(ProjectWorkspaceLayout layout, ProjectLocation project) throws Exception {
        layout.prepareWorkspace(project.workspaceId(), false);
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(layout.project(project).resolve(area));
        }
        layout.register(project);
    }
}
