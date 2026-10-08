package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实容器验证并行命令、持久文件及单条命令的后台进程清理。
 */
class DockerProjectCommandExecutorTest {
    @TempDir
    Path root;
    private final DockerWorkspaceCommands docker = new DockerWorkspaceCommands();
    private final Set<String> containers = new HashSet<>();

    private ProjectWorkspaceLayout layout() {
        return new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), new ObjectMapper());
    }

    private WorkspaceSettings settings() {
        return new WorkspaceSettings(root.toString(), "python:3.13-slim", true, 16, 1, 200, 256, 1, 32, 25, "none");
    }

    private DockerProjectCommandExecutor executor() {
        var runtime = new SandboxRuntimeSettings(32, 2, "", "");
        return new DockerProjectCommandExecutor(settings(), runtime, layout(), new UserContainerRuntime(settings(), runtime, layout(), docker), docker);
    }

    private ProjectLocation project(String user, String id) throws Exception {
        String enterprise = "enterprise-" + root.getFileName();
        var location = new ProjectLocation(ProjectPaths.workspaceId(enterprise, user), id, enterprise, user, "projects/" + id);
        var layout = layout();
        layout.prepareWorkspace(location.workspaceId(), false);
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(layout.project(location).resolve(area));
        }
        layout.register(location);
        containers.add(containerName(location));
        return location;
    }

    private String state(ProjectLocation project, String format) {
        return docker.require(List.of("inspect", "--type=container", "--format=" + format, containerName(project)),
            Duration.ofSeconds(10), null).output().trim();
    }

    private String containerName(ProjectLocation project) {
        return UserContainerOwnership.name(layout().hostPath(layout().files(project.workspaceId())));
    }

    @AfterEach
    void cleanup() {
        for (String container : containers) {
            docker.remove(container);
        }
    }

    @Test
    void reusesUserContainerAndKeepsProjectFilesAcrossRecreation() throws Exception {
        var first = project("alice", "report");
        var second = project("alice", "other");
        Files.writeString(layout().project(first).resolve("inputs/source.txt"), "输入原文");
        String command = """
            python - <<'PY'
            from pathlib import Path
            import os
            assert Path.cwd() == Path('/workspace/projects/report')
            assert os.getuid() != 0
            assert not Path('/var/run/docker.sock').exists()
            assert Path('/workspace/projects/other').is_dir()
            assert not Path('/workspace/control').exists()
            assert Path('inputs/source.txt').read_text() == '输入原文'
            Path('report.txt').write_text('第一轮', encoding='utf-8')
            PY
            """;
        try (var control = new ToolCallControl(() -> {
        })) {
            assertEquals(0, executor().execute(first, "first", command, null, Duration.ofSeconds(20), control).exitCode());
            String firstRevision = layout().fileRevision(first.workspaceId());
            assertFalse(firstRevision.isEmpty());
            String id = state(first, "{{.Id}}");
            assertEquals("true", state(first, "{{.State.Running}}"));
            assertEquals(0, executor().execute(first, "second", "printf '第二轮' >> report.txt", ".", Duration.ofSeconds(20), control).exitCode());
            assertEquals(id, state(first, "{{.Id}}"));
            assertEquals("第一轮第二轮", Files.readString(layout().project(first).resolve("report.txt")));
            assertEquals(0, executor().execute(second, "other", "test ! -e report.txt && printf '另一项目' > report.txt", ".", Duration.ofSeconds(20), control).exitCode());
            assertEquals(id, state(second, "{{.Id}}"), "切换项目只改变命令目录，不能重建用户容器");
            assertEquals(0, executor().execute(first, "authorized-sibling", "test -f report.txt && printf '已授权共享' > shared.txt",
                "../other", Duration.ofSeconds(20), control).exitCode());
            assertEquals("已授权共享", Files.readString(layout().project(second).resolve("shared.txt")));
            assertNotEquals(firstRevision, layout().fileRevision(second.workspaceId()));
            assertEquals(id, state(first, "{{.Id}}"));
            docker.remove(containerName(first));
            assertEquals(0, executor().execute(first, "recreated", "test -f report.txt", "/workspace/projects/report", Duration.ofSeconds(20), control).exitCode());
        }
        assertEquals("另一项目", Files.readString(layout().project(second).resolve("report.txt")));
        assertFalse(Files.exists(layout().control(first.workspaceId()).resolve("container.pending")));
    }

    @Test
    void commandsFromIndependentExecutorsRunTogetherAndDoNotHoldTheFileLock() throws Exception {
        var project = project("alice", "shared");
        String command = """
            python - <<'PY'
            from pathlib import Path
            import time
            Path('%s').touch()
            deadline = time.monotonic() + 8
            while not Path('%s').exists():
                if time.monotonic() > deadline:
                    raise RuntimeError('另一条命令未能并行启动')
                time.sleep(0.05)
            time.sleep(0.5)
            PY
            """;
        try (var control = new ToolCallControl(() -> {
        }); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(0, executor().execute(project, "ready", "true", ".", Duration.ofSeconds(25), control).exitCode());
            var first = pool.submit(() -> executor().execute(project, "a", command.formatted("a.ready", "b.ready"), ".", Duration.ofSeconds(25), control));
            var second = pool.submit(() -> executor().execute(project, "b", command.formatted("b.ready", "a.ready"), ".", Duration.ofSeconds(25), control));
            assertEquals(0, first.get(30, TimeUnit.SECONDS).exitCode());
            assertEquals(0, second.get(30, TimeUnit.SECONDS).exitCode());
            try (var lock = UserWorkspaceLock.acquire(layout(), project.workspaceId(), Duration.ofSeconds(1), control)) {
                assertTrue(Files.exists(layout().project(project).resolve("a.ready")));
            }
        }
        assertEquals("true", state(project, "{{.State.Running}}"));
    }

    @Test
    void timeoutAndBackgroundProcessesStopWhileWrittenFilesRemain() throws Exception {
        var project = project("alice", "timeout");
        try (var control = new ToolCallControl(() -> {
        })) {
            assertEquals(0, executor().execute(project, "ready", "true", ".", Duration.ofSeconds(20), control).exitCode());
            // 先确认容器可用，并为 Docker 检查留出时间，避免环境准备超时掩盖命令运行中的超时行为。
            var result = executor().execute(project, "timeout", "printf '已保存' > report.txt; sleep 40", ".", Duration.ofSeconds(15), control);
            assertTrue(result.timedOut());
            assertEquals("已保存", Files.readString(layout().project(project).resolve("report.txt")));
            assertEquals("true", state(project, "{{.State.Running}}"));
            executor().execute(project, "background", "(sleep 2; touch late.txt) &", ".", Duration.ofSeconds(20), control);
            Thread.sleep(2500);
            assertFalse(Files.exists(layout().project(project).resolve("late.txt")));
        }
    }

    @Test
    void cancellationStopsOnlyItsOwnCommandAndLeavesFilesAvailable() throws Exception {
        var project = project("alice", "cancel");
        try (var control = new ToolCallControl(() -> {
        }); var peerControl = new ToolCallControl(() -> {
        }); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(0, executor().execute(project, "ready", "true", ".", Duration.ofSeconds(25), peerControl).exitCode());
            var running = pool.submit(() -> executor().execute(project, "cancel", "touch started.txt; sleep 40", ".", Duration.ofSeconds(25), control));
            var peer = pool.submit(() -> executor().execute(project, "peer", "touch peer-started.txt; while [ ! -f peer-release.txt ]; do sleep 0.1; done; echo continued > peer.txt",
                ".", Duration.ofSeconds(25), peerControl));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while ((!Files.exists(layout().project(project).resolve("started.txt"))
                || !Files.exists(layout().project(project).resolve("peer-started.txt"))) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(layout().project(project).resolve("started.txt")));
            assertTrue(Files.exists(layout().project(project).resolve("peer-started.txt")));
            try (var lock = UserWorkspaceLock.acquire(layout(), project.workspaceId(), Duration.ofSeconds(1), peerControl)) {
                assertFalse(peer.isDone(), "另一条命令仍在运行时也可以读取项目文件");
            }
            control.close();
            assertThrows(Exception.class, () -> running.get(10, TimeUnit.SECONDS));
            Files.writeString(layout().project(project).resolve("peer-release.txt"), "继续");
            assertEquals(0, peer.get(15, TimeUnit.SECONDS).exitCode());
            assertEquals("continued", Files.readString(layout().project(project).resolve("peer.txt")).trim());
            assertEquals("true", state(project, "{{.State.Running}}"));
        }
    }
}
