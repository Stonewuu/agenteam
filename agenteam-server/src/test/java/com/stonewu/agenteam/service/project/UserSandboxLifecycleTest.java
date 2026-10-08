package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.SandboxRunUsage;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSandboxLifecycleTest {
    @TempDir
    Path root;
    private final TestClock clock = new TestClock();
    private final UserContainerRuntime containers = mock(UserContainerRuntime.class);
    private final ProjectCommandProcess processes = mock(ProjectCommandProcess.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final String container = "a".repeat(64);
    private ProjectWorkspaceLayout layout;
    private UserSandboxRegistry registry;
    private ProjectLocation project;
    private UserSandboxLifecycle lifecycle;

    @BeforeEach
    void prepare() throws Exception {
        layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
        registry = new UserSandboxRegistry(layout);
        project = new ProjectLocation(ProjectPaths.workspaceId("enterprise", "alice"), "project", "enterprise", "alice", "projects/project");
        layout.prepareWorkspace(project.workspaceId(), false);
        Files.createDirectories(layout.project(project));
        layout.register(project);
        when(containers.daemon(any(), any())).thenReturn("daemon");
        when(containers.ownedId(any(), any())).thenReturn(container);
        when(containers.running(anyString(), any())).thenAnswer(call -> running.get());
        doAnswer(call -> {
            running.set(true);
            return null;
        }).when(containers).start(any(), any(), any());
        doAnswer(call -> {
            running.set(false);
            return null;
        }).when(containers).stopInstance(any(), anyString(), anyInt(), any());
        when(processes.status(any(), any())).thenReturn(new ProjectCommandProcess.Status("running", null, false, null));
        lifecycle = restored();
    }

    @Test
    void commandsAndParentUsageSurviveRestartAndIdleStartsAfterTheLastRelease() throws Exception {
        try (var control = control()) {
            var record = lifecycle.begin(project, "long", "hash", "sleep 2000", Duration.ofHours(2), control);
            Files.writeString(layout.project(project).resolve("keep.txt"), "保留文件");
            clock.advance(1000);
            lifecycle = restored();
            assertFalse(lifecycle.collect(project.workspaceId()), "无输出的长命令仍然受到保护");
            lifecycle.complete(record, new SandboxExecutor.Result(0, false, false, "out", "err"));
            var usage = new SandboxRunUsage("run", 1, "child", project, "job", "worker", clock.millis());
            registry.save(usage);
            clock.advance(1000);
            assertFalse(lifecycle.collect(project.workspaceId()), "父任务或子任务仍在使用容器");
            registry.remove(usage);
            registry.markActivity(project.workspaceId(), clock.millis());
            clock.advance(899);
            assertFalse(lifecycle.collect(project.workspaceId()));
            clock.advance(2);
            assertTrue(lifecycle.collect(project.workspaceId()));
            assertFalse(running.get());
            assertEquals("保留文件", Files.readString(layout.project(project).resolve("keep.txt")));
            var next = lifecycle.begin(project, "next", "next-hash", "true", Duration.ofMinutes(1), control);
            assertTrue(running.get());
            assertNotEquals(record.generation(), next.generation());
        }
    }

    @Test
    void aNewCommandWaitsForStopAndDelayedCollectorsCannotStopTheRestartedInstance() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            running.set(false);
            return null;
        }).when(containers).stopInstance(any(), anyString(), anyInt(), any());
        try (var control = control(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = lifecycle.begin(project, "first", "hash", "true", Duration.ofMinutes(1), control);
            lifecycle.complete(first, new SandboxExecutor.Result(0, false, false, "out", "err"));
            clock.advance(901);
            var collecting = pool.submit(() -> lifecycle.collect(project.workspaceId()));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var second = pool.submit(() -> {
                started.countDown();
                return lifecycle.begin(project, "second", "second-hash", "true", Duration.ofMinutes(1), control);
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            finish.countDown();
            assertTrue(collecting.get(5, TimeUnit.SECONDS));
            assertNotEquals(first.generation(), second.get(5, TimeUnit.SECONDS).generation());
            assertTrue(running.get());
            verify(containers, times(1)).stopInstance(any(), anyString(), anyInt(), any());
        } finally {
            finish.countDown();
        }
    }

    @Test
    void aRepeatedCallDoesNotRunTwiceAndMissingProcessesAreCancelledBeforeLateLaunch() throws Exception {
        try (var control = control()) {
            var first = lifecycle.begin(project, "first", "hash", "true", Duration.ofHours(2), control);
            assertThrows(ApiException.class, () -> lifecycle.begin(project, "first", "hash", "true", Duration.ofMinutes(1), control));
            lifecycle.complete(first, new SandboxExecutor.Result(0, false, false, "out", "err"));
            assertEquals(0, lifecycle.begin(project, "first", "hash", "true", Duration.ofMinutes(1), control).result().exitCode());
            var lost = lifecycle.begin(project, "lost", "lost-hash", "true", Duration.ofHours(2), control);
            when(processes.status(any(), any())).thenReturn(new ProjectCommandProcess.Status("missing", null, false, null));
            when(processes.cancel(any(), any())).thenReturn(new ProjectCommandProcess.Status("completed", 143, false, null));
            clock.advance(61);
            assertFalse(restored().collect(project.workspaceId()));
            assertThrows(ApiException.class, () -> lifecycle.begin(project, "lost", "lost-hash", "true", Duration.ofMinutes(1), control));
            assertEquals("interrupted", registry.command(project.workspaceId(), "lost").status());
            assertEquals(lost.token(), registry.command(project.workspaceId(), "lost").token());
            assertTrue(registry.active(project.workspaceId()).isEmpty());
            verify(processes, times(2)).prepare(any(), anyString(), anyString());
        }
    }

    private UserSandboxLifecycle restored() {
        return new UserSandboxLifecycle(layout, registry, containers, processes, new SandboxLifecycleSettings(900, 30, 60, 10), clock);
    }

    private ToolCallControl control() {
        return new ToolCallControl(() -> {
        });
    }

    static final class TestClock extends Clock {
        private volatile long millis = 100000;

        void advance(long seconds) {
            millis += seconds * 1000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
