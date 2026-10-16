package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 Docker 容器确认命令隔离、完整输出和实际进程终止。
 */
@Tag("external-process")
class DockerWorkspaceRunnerTest {
    @TempDir
    Path directory;
    private final DockerWorkspaceCommands docker = new DockerWorkspaceCommands();

    private WorkspaceSettings settings() {
        return new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 16, 1, 50, 256, 1, 32, 15, "none");
    }

    private Path source() throws Exception {
        Path source = directory.resolve("saved");
        for (String area : List.of("work", "outputs", "inputs", "tool-results")) {
            Files.createDirectories(source.resolve(area));
        }
        Files.writeString(source.resolve("work/original.txt"), "不可修改的已保存内容");
        return source;
    }

    private String containers() {
        return docker.run(List.of("ps", "-aq", "--filter", "label=com.stonewu.agenteam.workspace=" + Utf8Text.revision("workspace", directory.toString())), Duration.ofSeconds(5)).output().trim();
    }

    @AfterEach
    void cleanIncompleteTestResources() {
        if (Files.isRegularFile(directory.resolve("sandbox.pending"))) {
            docker.removeForWorkspace(Utf8Text.revision("workspace", directory.toAbsolutePath().normalize().toString()));
        }
    }

    @Test
    void rootCommandKeepsInputsReadOnlyCapturesFullOutputAndStopsBackgroundProcesses() throws Exception {
        Path source = source(), output = directory.resolve("result");
        Files.writeString(source.resolve("inputs/note.txt"), "只读输入");
        Files.writeString(source.resolve("tool-results/result.txt"), "只读工具结果");
        var runner = new DockerWorkspaceRunner(settings(), docker);
        try (var control = new ToolCallControl(() -> {
        })) {
            String command = """
                (while true; do printf '后台写入\n' >> work/background.txt; sleep 1; done) &
                while [ ! -s work/background.txt ]; do sleep 0.01; done
                python - <<'PY'
                import os, sys
                from pathlib import Path
                assert Path.cwd() == Path('/workspace')
                assert os.getuid() == 65534
                assert len(Path('/proc/net/route').read_text().strip().splitlines()) == 1
                assert not os.environ.get('HTTP_PROXY')
                assert not Path('/var/run/docker.sock').exists()
                for path in ('/agenteam-saved/work/original.txt', 'inputs/note.txt', 'tool-results/result.txt'):
                    try:
                        Path(path).write_text('不应写入', encoding='utf-8')
                    except OSError:
                        pass
                    else:
                        raise AssertionError('只读资料被允许修改')
                Path('outputs/report.txt').write_text(Path('work/original.txt').read_text(encoding='utf-8') + '：已处理', encoding='utf-8')
                sys.stdout.write('文🙂' * 100000)
                sys.stderr.write('完整错误输出')
                PY
                """;
            var result = runner.execute(source, output, directory, "large-output", command, "/workspace", Duration.ofSeconds(15), control);
            assertEquals(0, result.exitCode());
            assertFalse(result.timedOut());
            assertEquals("文🙂".repeat(100000), Files.readString(output.resolve(result.stdoutPath()), StandardCharsets.UTF_8));
            assertEquals("完整错误输出", Files.readString(output.resolve(result.stderrPath()), StandardCharsets.UTF_8));
            assertEquals("不可修改的已保存内容：已处理", Files.readString(output.resolve("outputs/report.txt")));
            assertEquals("不可修改的已保存内容", Files.readString(source.resolve("work/original.txt")));
            assertEquals("只读输入", Files.readString(source.resolve("inputs/note.txt")));
            assertEquals("只读工具结果", Files.readString(source.resolve("tool-results/result.txt")));
            assertTrue(Files.readString(output.resolve("work/background.txt")).contains("后台写入"));
            assertFalse(Files.exists(source.resolve("outputs/report.txt")));
        }
        assertEquals("", containers(), "调用结束后不能留有后台容器");
    }

    @Test
    void configuredSharedCapacityBlocksCommandsAcrossRunnerInstances() throws Exception {
        var runtime = new SandboxRuntimeSettings(64, 1, "", "", directory.resolve("capacity").toString(), "");
        var runner = new DockerWorkspaceRunner(settings(), docker, runtime);
        var source = source();
        try (var control = new ToolCallControl(() -> {
        })) {
            try (var occupied = new SandboxCapacity(runtime).acquire(Duration.ofSeconds(5), control)) {
                var failure = assertThrows(ApiException.class, () -> runner.execute(source, directory.resolve("blocked"),
                    directory, "blocked", "true", "work", Duration.ofMillis(150), control));
                assertEquals("SANDBOX_QUEUE_TIMEOUT", failure.code());
                assertEquals("", containers(), "等待共享名额期间不能启动命令容器");
            }
            var result = runner.execute(source, directory.resolve("result"), directory, "released", "true", "work",
                Duration.ofSeconds(15), control);
            assertEquals(0, result.exitCode());
        }
        assertEquals("", containers());
    }

    @Test
    void timedOutCommandPreservesActualOutputAndTerminatesTheContainer() throws Exception {
        var runner = new DockerWorkspaceRunner(settings(), docker);
        try (var control = new ToolCallControl(() -> {
        })) {
            // 总期限还包含容器准备和输出回收；必须让命令真正开始后再验证超时结果。
            var result = runner.execute(source(), directory.resolve("result"), directory, "timeout", "printf '超时前输出'; sleep 30", "work", Duration.ofSeconds(12), control);
            assertEquals(124, result.exitCode());
            assertTrue(result.timedOut());
            assertEquals("超时前输出", Files.readString(directory.resolve("result").resolve(result.stdoutPath())));
        }
        assertEquals("", containers());
    }

    @Test
    void cleanupFailurePreservesTheActualExitCodeAndLeavesARecordForLaterCleanup() throws Exception {
        var failingCleanup = new DockerWorkspaceCommands() {
            @Override
            public void removeVolume(String name, Duration timeout) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_UNAVAILABLE", "测试临时卷暂时不能清理。");
            }
        };
        var source = source();
        var submissions = new AtomicInteger();
        try {
            try (var control = new ToolCallControl(submissions::incrementAndGet)) {
                var output = directory.resolve("cleanup-result");
                var result = new DockerWorkspaceRunner(settings(), failingCleanup).execute(source, output, directory, "cleanup-result",
                    "printf '已取得的命令输出'; exit 7", "work", Duration.ofSeconds(15), control);
                assertEquals(7, result.exitCode());
                assertEquals("已取得的命令输出", Files.readString(output.resolve(result.stdoutPath())));
                assertTrue(Files.exists(directory.resolve("sandbox.pending")));
            }
            assertEquals(0, submissions.get(), "无网络命令在保存工作文件前不能被记成已提交修改");
        } finally {
            docker.removeForWorkspace(Utf8Text.revision("workspace", directory.toString()));
        }
    }

    @Test
    void cleanupFailureDoesNotReplaceTheFailureBeforeACommandStarts() throws Exception {
        var preparation = new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SANDBOX_TIMEOUT", "测试沙盒准备超时。");
        var failing = new DockerWorkspaceCommands() {
            @Override
            public void removeForWorkspace(String scope, Duration timeout, ToolCallControl control) {
            }

            @Override
            public Result require(List<String> arguments, Duration timeout, ToolCallControl control) {
                throw preparation;
            }

            @Override
            public void remove(String container, Duration timeout) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_UNAVAILABLE", "测试容器清理失败。");
            }

            @Override
            public void removeVolume(String name, Duration timeout) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_UNAVAILABLE", "测试临时卷清理失败。");
            }
        };
        var submissions = new AtomicInteger();
        try (var control = new ToolCallControl(submissions::incrementAndGet)) {
            var source = source();
            var failure = assertThrows(ApiException.class, () -> new DockerWorkspaceRunner(settings(), failing)
                .execute(source, directory.resolve("result"), directory, "preparation", "echo ok", "work", Duration.ofSeconds(10), control));
            assertSame(preparation, failure);
            assertEquals(0, submissions.get());
        }
    }

    @Test
    void cancellationKillsTheRunningProcessAndDoesNotPublishAWorkspace() throws Exception {
        var runner = new DockerWorkspaceRunner(settings(), docker);
        var source = source();
        try (var control = new ToolCallControl(() -> {
        }); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = threads.submit(() -> runner.execute(source, directory.resolve("result"), directory, "cancel", "touch started; sleep 60; echo '不应写入' > changed", "work", Duration.ofSeconds(15), control));
            long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
            boolean started = false;
            while (System.nanoTime() < deadline && !task.isDone()) {
                String id = containers();
                if (!id.isBlank() && docker.run(List.of("exec", id, "test", "-f", "/workspace/work/started"), Duration.ofSeconds(2)).exitCode() == 0) {
                    started = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(started, "必须先确认命令已经执行，再验证取消");
            control.close();
            assertThrows(Exception.class, () -> task.get(10, TimeUnit.SECONDS));
            assertEquals("", containers());
            assertFalse(Files.exists(source.resolve("work/changed")));
            assertFalse(Files.exists(directory.resolve("result/work/changed")));
        }
    }

    @Test
    void fileAndWorkspaceLimitsStopExcessiveWrites() throws Exception {
        var runner = new DockerWorkspaceRunner(settings(), docker);
        var source = source();
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = runner.execute(source, directory.resolve("file-limit"), directory, "file-limit",
                "python -c \"open('huge.bin','wb').write(bytes(2097152))\"", "work", Duration.ofSeconds(15), control);
            assertNotEquals(0, result.exitCode());
            assertTrue(Files.size(directory.resolve("file-limit/work/huge.bin")) <= settings().fileBytes());
        }
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = runner.execute(source, directory.resolve("space-limit"), directory, "space-limit",
                "python -c \"from pathlib import Path; [Path(f'data-{i}.bin').write_bytes(bytes(1048576)) for i in range(30)]\"", "work", Duration.ofSeconds(15), control);
            assertNotEquals(0, result.exitCode());
            assertTrue(result.capacityExceeded(), "空间耗尽时仍需返回具体原因，错误输出文件也可能已无法继续写入");
        }

    }
}
