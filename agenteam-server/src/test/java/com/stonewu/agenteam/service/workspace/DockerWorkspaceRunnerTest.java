package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void commandRunsWithReadOnlyInputsAndKeepsOutputBeyondTheSdkCaptureLimit() throws Exception {
        Path source = source(), output = directory.resolve("result");
        var runner = new DockerWorkspaceRunner(settings(), docker);
        try (var control = new ToolCallControl(() -> {
        })) {
            String command = "python - <<'PY'\nimport os, sys\nassert os.getuid() == 65534\nassert len(open('/proc/net/route').read().strip().splitlines()) == 1\nassert not os.environ.get('HTTP_PROXY')\ntry:\n open('/agenteam-saved/work/original.txt', 'w').write('不能写入')\n raise AssertionError('输入目录可写')\nexcept OSError:\n pass\nassert not os.path.exists('/var/run/docker.sock')\nopen('/workspace/outputs/report.txt','w').write(open('original.txt').read() + '：已处理')\nsys.stdout.write('文🙂' * 100000)\nsys.stderr.write('完整错误输出')\nPY";
            var result = runner.execute(source, output, directory, "large-output", command, "work", Duration.ofSeconds(15), control);
            assertEquals(0, result.exitCode());
            assertFalse(result.timedOut());
            assertEquals("文🙂".repeat(100000), Files.readString(output.resolve(result.stdoutPath()), StandardCharsets.UTF_8));
            assertEquals("完整错误输出", Files.readString(output.resolve(result.stderrPath()), StandardCharsets.UTF_8));
            assertEquals("不可修改的已保存内容：已处理", Files.readString(output.resolve("outputs/report.txt")));
            assertEquals("不可修改的已保存内容", Files.readString(source.resolve("work/original.txt")));
        }
        assertEquals("", containers(), "调用结束后不能留有后台容器");
    }

    @Test
    void timedOutCommandPreservesActualOutputAndTerminatesTheContainer() throws Exception {
        var runner = new DockerWorkspaceRunner(settings(), docker);
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = runner.execute(source(), directory.resolve("result"), directory, "timeout", "printf '超时前输出'; sleep 30", "work", Duration.ofSeconds(4), control);
            assertEquals(124, result.exitCode());
            assertTrue(result.timedOut());
            assertEquals("超时前输出", Files.readString(directory.resolve("result").resolve(result.stdoutPath())));
        }
        assertEquals("", containers());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workspace", "/workspace/", "/", "."})
    void workspaceRootCanRunCommandsWithoutMakingInputsWritable(String workingDirectory) throws Exception {
        Path source = source(), output = directory.resolve("root-result");
        Files.writeString(source.resolve("inputs/note.txt"), "只读输入");
        Files.writeString(source.resolve("tool-results/result.txt"), "只读工具结果");
        String command = """
            python - <<'PY'
            from pathlib import Path
            assert Path.cwd() == Path('/workspace')
            for path in ('inputs/note.txt', 'tool-results/result.txt'):
                try:
                    Path(path).write_text('不应写入', encoding='utf-8')
                except OSError:
                    pass
                else:
                    raise AssertionError('只读资料被允许修改')
            Path('outputs/from-root.txt').write_text(Path('work/original.txt').read_text(encoding='utf-8') + '：根目录执行成功', encoding='utf-8')
            print(Path.cwd())
            PY
            """;
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = new DockerWorkspaceRunner(settings(), docker).execute(source, output, directory,
                "root", command, workingDirectory, Duration.ofSeconds(15), control);
            assertEquals(0, result.exitCode());
            assertEquals("/workspace", Files.readString(output.resolve(result.stdoutPath())).trim());
            assertEquals("不可修改的已保存内容：根目录执行成功", Files.readString(output.resolve("outputs/from-root.txt")));
            assertEquals("只读输入", Files.readString(source.resolve("inputs/note.txt")));
            assertEquals("只读工具结果", Files.readString(source.resolve("tool-results/result.txt")));
            assertFalse(Files.exists(source.resolve("outputs/from-root.txt")), "命令结果只写入待保存目录");
        }
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
            for (int exit : List.of(0, 7)) {
                try (var control = new ToolCallControl(submissions::incrementAndGet)) {
                    var output = directory.resolve("cleanup-" + exit);
                    var result = new DockerWorkspaceRunner(settings(), failingCleanup).execute(source, output, directory, "cleanup-" + exit,
                        "printf '已取得的命令输出'; exit " + exit, "work", Duration.ofSeconds(15), control);
                    assertEquals(exit, result.exitCode());
                    assertEquals("已取得的命令输出", Files.readString(output.resolve(result.stdoutPath())));
                    assertTrue(Files.exists(directory.resolve("sandbox.pending")));
                }
                docker.removeForWorkspace(Utf8Text.revision("workspace", directory.toString()));
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
    void fileAndWorkspaceLimitsStopExcessiveWritesAndRemoveBackgroundProcesses() throws Exception {
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
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = runner.execute(source, directory.resolve("background"), directory, "background",
                "(while true; do echo '后台写入' >> background.txt; sleep 1; done) & echo '主命令结束'", "work", Duration.ofSeconds(15), control);
            assertEquals(0, result.exitCode());
            assertEquals("", containers());
        }
    }
}
