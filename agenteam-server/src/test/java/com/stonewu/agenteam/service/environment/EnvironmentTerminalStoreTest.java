package com.stonewu.agenteam.service.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.UserSandboxState;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectPaths;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserContainerRuntime;
import com.stonewu.agenteam.service.project.UserSandboxRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnvironmentTerminalStoreTest {
    @TempDir Path root;

    @Test
    void sharedConnectionOrdersInputBoundsOutputAndDoesNotRenewIdleOnPolling() throws Exception {
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
        var project = new ProjectLocation(ProjectPaths.workspaceId("enterprise", "alice"), "user-environment", "enterprise", "alice", "environment");
        layout.prepareWorkspace(project.workspaceId(), false);
        Files.createDirectories(layout.project(project));
        layout.register(project);
        var store = new EnvironmentTerminalStore(layout);
        var registry = new UserSandboxRegistry(layout);
        var containers = mock(UserContainerRuntime.class);
        var worker = mock(EnvironmentTerminalWorker.class);
        var clock = Clock.fixed(Instant.ofEpochMilli(100000), ZoneOffset.UTC);
        when(containers.daemon(any(), any())).thenReturn("daemon");
        when(containers.ownedId(any(), any())).thenReturn("container");
        when(containers.running(any(), any())).thenReturn(true);
        registry.save(new UserSandboxState(project, "container", "generation", "daemon", "running", 1000, 1000));
        try (var first = new DockerUserTerminalRuntime(store, worker, registry, containers, clock);
             var second = new DockerUserTerminalRuntime(store, worker, registry, containers, clock)) {
            String id = UUID.randomUUID().toString();
            assertEquals(id, first.exchange(project, request("open", id, "", 0)).id());
            assertEquals(id, second.exchange(project, request("open", UUID.randomUUID().toString(), "", 0)).id());
            verify(worker, timeout(2000).times(1)).run(eq(project), any(), any());
            registry.markActivity(project.workspaceId(), 2000);
            first.exchange(project, request("poll", id, "", 0));
            assertEquals(2000, registry.state(project.workspaceId()).idleSince());
            var input = request("input", id, "echo hello\r", 1);
            first.exchange(project, input);
            second.exchange(project, input);
            assertEquals("echo hello\r", store.read(project).pendingInput);
            assertEquals(100000, registry.state(project.workspaceId()).idleSince());
            assertThrows(ApiException.class, () -> second.exchange(project, request("input", id, "other", 1)));
            store.locked(project, state -> {
                state.pendingInput = "";
                byte[] output = new byte[16384];
                for (int i = 0; i < 180; i++) {
                    store.append(project, state, output);
                }
                store.save(project, state);
                return null;
            });
            second.exchange(project, input);
            assertEquals("", store.read(project).pendingInput, "已消费的输入也不能因请求重试再次执行");
            var replay = first.exchange(project, request("poll", id, "", 1));
            assertTrue(replay.start() > 0);
            assertTrue(Files.size(layout.control(project.workspaceId()).resolve("terminal-output.bin")) <= 2 * 1024 * 1024);
            assertTrue(replay.end() - replay.start() <= 65536);
            second.exchange(project, request("close", id, "", 1));
            assertEquals("closing", store.read(project).status);
            assertTrue(registry.interactive(project.workspaceId(), 100000));
            assertFalse(registry.interactive(project.workspaceId(), 161000));
        }
    }

    private EnvironmentTerminalInput request(String action, String id, String data, long sequence) {
        return new EnvironmentTerminalInput(action, id, data, sequence, 0, 100, 30);
    }
}
