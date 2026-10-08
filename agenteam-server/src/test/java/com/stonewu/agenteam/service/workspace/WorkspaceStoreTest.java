package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceStoreTest {
    @TempDir
    Path directory;
    private final RuntimeContext context = RuntimeContext.empty();
    private final ToolCallControl control = new ToolCallControl(() -> {
    });

    private WorkspaceStore store() {
        return new WorkspaceStore(new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 16, 1, 20, 256, 1, 32, 30, "none"), new ObjectMapper());
    }

    private RunRecord run(String id, String conversation) {
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn(conversation);
        when(run.id()).thenReturn(id);
        when(run.leaseVersion()).thenReturn(1L);
        return run;
    }

    @Test
    void laterRunReadsTheSameFilesAndExactEditKeepsTheirContents() throws Exception {
        var store = store();
        try (var scope = store.open(run("first", "conversation"), "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            scope.prepare().write(context, "work/report.txt", "第一行🙂\n旧内容\n最后一行");
            scope.commit(Set.of("source"), Set.of());
        }
        try (var scope = store.open(run("second", "conversation"), "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            assertEquals(Set.of("source"), scope.manifest().sourceResultIds());
            assertEquals("旧内容\n", scope.current().document("work/report.txt").read(2, 2, null, 10, 1024).content());
            assertEquals(1, scope.prepare().edit(context, "work/report.txt", "旧内容", "修改后的内容", false).occurrences());
            scope.commit(Set.of("source"), Set.of());
        }
        try (var scope = store.open(run("third", "conversation"), "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            assertEquals("第一行🙂\n修改后的内容\n最后一行", scope.current().document("work/report.txt").read(1, null, null, 10, 1024).content());
        }
    }

    @Test
    void cancelledOrExpiredOwnerCannotPublishItsPreparedChanges() throws Exception {
        var store = store();
        var expired = new AtomicBoolean(false);
        try (var scope = store.open(run("first", "conversation"), "conversation", control, Duration.ofSeconds(1), () -> {
            if (expired.get()) {
                throw new ExecutionStoppedException();
            }
        })) {
            scope.prepare().write(context, "work/unfinished.txt", "未确认的内容");
            expired.set(true);
            assertThrows(ExecutionStoppedException.class, () -> scope.commit(Set.of(), Set.of()));
        }
        try (var scope = store.open(run("second", "conversation"), "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            assertFalse(scope.current().exists(context, "work/unfinished.txt"));
        }
    }

    @Test
    void conversationUserAndChildDirectoriesRemainSeparate() throws Exception {
        var store = store();
        var parent = run("run", "conversation");
        try (var scope = store.open(parent, "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            scope.prepare().write(context, "work/private.txt", "当前对话内容");
            scope.commit(Set.of(), Set.of());
        }
        for (String session : Set.of("child", "other-conversation")) {
            try (var scope = store.open(session.equals("child") ? parent : run("other", session), session, control, Duration.ofSeconds(1), () -> {
            })) {
                assertFalse(scope.current().exists(context, "work/private.txt"));
            }
        }
        var other = run("run", "conversation");
        when(other.userId()).thenReturn("other-user");
        try (var scope = store.open(other, "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            assertFalse(scope.current().exists(context, "work/private.txt"));
        }
    }

    @Test
    void concurrentOwnerWaitsUntilThePreviousScopeHasClosed() throws Exception {
        var store = store();
        var run = run("run", "conversation");
        try (var ignored = store.open(run, "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            assertEquals("WORKSPACE_BUSY", assertThrows(ApiException.class, () -> store.open(run, "conversation", control, Duration.ofMillis(60), () -> {
            })).code());
        }
        try (var available = store.open(run, "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            assertNotNull(available.current());
        }
    }

    @Test
    void browsingReadsTheCommittedVersionWithoutWaitingForTheWriter() throws Exception {
        var store = store();
        var run = run("run", "conversation");
        try (var scope = store.open(run, "conversation", control, Duration.ofSeconds(10), () -> {
        })) {
            scope.prepare().write(context, "work/saved.txt", "已保存");
            scope.commit(Set.of("source"), Set.of());
        }
        try (var writer = store.open(run, "conversation", control, Duration.ofSeconds(10), () -> {
        })) {
            writer.prepare().edit(context, "work/saved.txt", "已保存", "尚未保存", false);
            var snapshot = assertTimeout(Duration.ofSeconds(1), () -> store.snapshot("enterprise", "user", "conversation"));
            assertEquals(Set.of("source"), snapshot.manifest().sourceResultIds());
            assertEquals("已保存", snapshot.files().document("work/saved.txt").read(1, null, null, 10, 1024).content());
            assertNull(store.snapshot("enterprise", "other-user", "conversation").files());
            assertNull(store.snapshot("enterprise", "user", "other-conversation").files());
            writer.commit(Set.of("source"), Set.of());
            assertEquals("尚未保存", store.snapshot("enterprise", "user", "conversation").files()
                .document("work/saved.txt").read(1, null, null, 10, 1024).content());
        }
    }

    @Test
    void rejectsHostPathsReadOnlyAreasAmbiguousEditsAndOversizedWrites() throws Exception {
        var store = store();
        try (var scope = store.open(run("run", "conversation"), "conversation", control, Duration.ofSeconds(1), () -> {
        })) {
            var files = scope.prepare();
            for (String path : Set.of("../outside", "C:/Windows/system.ini", "/etc/passwd", "work/../secret", "work/NUL", "work/a:stream", "inputs/new.txt", "tool-results/fake.json")) {
                assertThrows(ApiException.class, () -> files.write(context, path, "不应写入"));
            }
            files.write(context, "work/edit.txt", "重复\n重复");
            assertFalse(files.edit(context, "work/edit.txt", "重复", "变化", false).isSuccess());
            assertEquals(2, files.edit(context, "work/edit.txt", "重复", "变化", true).occurrences());
            assertThrows(ApiException.class, () -> files.write(context, "outputs/large.txt", "文".repeat(400000)));
        }
    }

    @Test
    void rootAliasesResolveInsideTheWorkspaceAndCannotBeWrittenAsFiles() throws Exception {
        for (String root : Set.of("/workspace", "/workspace/", "/", ".")) {
            assertEquals(directory, WorkspacePaths.resolve(directory, root, false));
            assertEquals("WORKSPACE_READ_ONLY", assertThrows(ApiException.class,
                () -> WorkspacePaths.resolve(directory, root, true)).code());
        }
        for (String path : Set.of("/workspace//", "/workspace/..", "/workspace/../outside", "/workspace/./work", "/workspace-other/work")) {
            assertEquals("WORKSPACE_PATH_INVALID", assertThrows(ApiException.class,
                () -> WorkspacePaths.resolve(directory, path, false)).code());
        }
    }
}
