package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.CommandResultSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.workspace.entity.WorkspaceLimitValues;
import com.stonewu.agenteam.model.workspace.request.WorkspaceLimitsRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.project.ProjectCapacityMonitor;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.support.TestOutputPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceLimitsManagementTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private WorkspaceLimitsStore store;
    private WorkspaceSettings workspace;
    private CommandResultSettings results;
    private WorkspaceLimitsManagement management;
    private UserEntity administrator;

    @BeforeEach
    void prepare() {
        root = TestOutputPaths.path("workspace-limits", UUID.randomUUID().toString());
        store = store();
        workspace = workspace(store);
        results = new CommandResultSettings(7, 256, 1000, store);
        management = new WorkspaceLimitsManagement(store, workspace, results);
        administrator = mock(UserEntity.class);
        when(administrator.superAdmin()).thenReturn(true);
        when(administrator.id()).thenReturn("settings-administrator");
    }

    @Test
    void savePersistsAndOtherRuntimeReadsNewLimitsWithoutRestart() throws Exception {
        var defaults = management.read(administrator);
        assertEquals("1", defaults.revision());
        assertEquals(128, defaults.workspaceMb());
        assertEquals(256, defaults.commandResultsMb());
        assertFalse(Files.exists(root));

        var executorStore = store();
        var executorWorkspace = workspace(executorStore);
        var executorResults = new CommandResultSettings(7, 256, 1000, executorStore);
        assertEquals(128, executorWorkspace.workspaceMb());
        var saved = management.save(administrator, 1, new WorkspaceLimitsRequest(256, 40, 4000, 512));
        assertEquals("2", saved.revision());
        assertEquals(256 * 1024L * 1024, executorWorkspace.workspaceBytes());
        assertEquals(40 * 1024 * 1024, executorWorkspace.fileBytes());
        assertEquals(4000, executorWorkspace.maximumFiles());
        assertEquals(512 * 1024L * 1024, executorResults.maximumBytes());
        assertEquals(saved, new WorkspaceLimitsManagement(store(), workspace(store()),
            new CommandResultSettings(7, 256, 1000, store())).read(administrator));

        var monitor = new ProjectCapacityMonitor(root, executorWorkspace, new AtomicBoolean());
        assertFalse(monitor.exceeded(new ProjectCapacityMonitor.Usage(200 * 1024L * 1024, 30 * 1024L * 1024, 6000)));
        management.save(administrator, 2, new WorkspaceLimitsRequest(128, 20, 2000, 1));
        assertTrue(monitor.exceeded(new ProjectCapacityMonitor.Usage(200 * 1024L * 1024, 30 * 1024L * 1024, 6000)));
        var output = root.resolve("output-cache");
        Files.createDirectories(output);
        Files.write(output.resolve("stdout.txt"), new byte[2 * 1024 * 1024]);
        var records = Files.createDirectories(root.resolve("output-records"));
        var cache = new CommandResultCache(executorResults, executorWorkspace, json, Clock.systemUTC());
        assertTrue(cache.capacityExceeded(new CommandResultCache.Area(output, records, "test")));
    }

    @Test
    void permissionsAndInvalidValuesCannotChangeSavedSettings() {
        var user = mock(UserEntity.class);
        assertEquals("WORKSPACE_SETTINGS_DENIED", assertThrows(ApiException.class, () -> management.read(user)).code());
        assertEquals("WORKSPACE_SETTINGS_DENIED", assertThrows(ApiException.class, () -> management.save(user, 1,
            new WorkspaceLimitsRequest(256, 40, 4000, 512))).code());
        for (var invalid : new WorkspaceLimitsRequest[]{new WorkspaceLimitsRequest(15, 1, 20, 1),
            new WorkspaceLimitsRequest(16, 20, 2000, 256), new WorkspaceLimitsRequest(128, 20, 9, 256),
            new WorkspaceLimitsRequest(128, 20, 2000, 0)}) {
            assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
                () -> management.save(administrator, 1, invalid)).code());
        }
        for (String input : new String[]{"{}", "{\"workspaceMb\":null}", "{\"workspaceMb\":16.5}",
            "{\"workspaceMb\":\"128\"}", "{\"unknown\":128}"}) {
            assertThrows(ApiException.class, () -> InputValidation.read(json.readTree(input), WorkspaceLimitsRequest.class, ""));
        }
        assertNull(store.current());
    }

    @Test
    void administratorsCanExceedFormerMaximumsWithoutIntegerOverflow() throws Exception {
        var saved = management.save(administrator, 1, new WorkspaceLimitsRequest(Integer.MAX_VALUE, 4096,
            1500000000, 1000000));
        assertEquals(Integer.MAX_VALUE, saved.workspaceMb());
        assertEquals(4096L * 1024 * 1024, workspace.fileBytes());
        assertEquals(Integer.MAX_VALUE * 1024L * 1024, workspace.workspaceBytes());
        assertEquals(1000000L * 1024 * 1024, results.maximumBytes());
        var configured = new WorkspaceSettings(root.toString(), "sandbox:test", true, 8192, 4096, 1500000000,
            512, 1, 128, 1800, "none");
        assertEquals(4096L * 1024 * 1024, configured.fileBytes());
        assertEquals(1000000L * 1024 * 1024, new CommandResultSettings(7, 1000000, 1000).maximumBytes());
        var monitor = new ProjectCapacityMonitor(root, workspace, new AtomicBoolean());
        assertFalse(monitor.exceeded(new ProjectCapacityMonitor.Usage(1, 1, 3000000000L)));
        assertTrue(monitor.exceeded(new ProjectCapacityMonitor.Usage(1, 1, 3000000001L)));
        Files.writeString(root.resolve("small.txt"), "容量设置大于 2 GB 仍可读取普通文件");
        var files = new WorkspaceFilesystem(root, workspace, () -> {
        }, true, true);
        assertEquals("容量设置大于 2 GB 仍可读取普通文件", files.document("small.txt").window(0, 1024, false).content());
    }

    @Test
    void concurrentAdministratorsCannotOverwriteTheSameVersion() throws Exception {
        var values = new WorkspaceLimitValues(256, 40, 4000, 512);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> saveOnce(store(), values));
            var second = workers.submit(() -> saveOnce(store(), values));
            assertEquals(1, first.get() + second.get());
        }
        assertEquals(2, store.current().revision());
        assertEquals("VERSION_CONFLICT", assertThrows(ApiException.class, () -> store.save(1, values)).code());
    }

    @Test
    void corruptSavedSettingsDoNotSilentlyRestoreDeploymentDefaults() throws Exception {
        var directory = Files.createDirectories(root.resolve(".platform-settings"));
        Files.writeString(directory.resolve("workspace-limits.json"), "{\"revision\":2,\"limits\":null}");
        assertEquals("WORKSPACE_SETTINGS_UNAVAILABLE", assertThrows(ApiException.class, () -> workspace.workspaceMb()).code());
    }

    private int saveOnce(WorkspaceLimitsStore target, WorkspaceLimitValues values) {
        try {
            target.save(1, values);
            return 1;
        } catch (ApiException failure) {
            assertEquals("VERSION_CONFLICT", failure.code());
            return 0;
        }
    }

    private WorkspaceLimitsStore store() {
        return new WorkspaceLimitsStore(new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), json));
    }

    private WorkspaceSettings workspace(WorkspaceLimitsStore target) {
        return new WorkspaceSettings(root.toString(), "sandbox:test", true, 128, 20, 2000, 512, 1, 128, 1800, "none", target);
    }
}
