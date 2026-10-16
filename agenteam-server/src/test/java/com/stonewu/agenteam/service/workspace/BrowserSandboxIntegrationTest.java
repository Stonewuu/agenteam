package com.stonewu.agenteam.service.workspace;

import org.junit.jupiter.api.Tag;

import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.file.Utf8Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在与生产一致的只读、非特权容器中验证网页脚本执行和代码拉取。 */
@Tag("external-process")
class BrowserSandboxIntegrationTest {
    @TempDir
    Path directory;
    private final DockerWorkspaceCommands docker = new DockerWorkspaceCommands();

    @Test
    void readsPagesAndClonesGitWithinTheMemoryBudgetAndReleasesEveryBrowser() throws Exception {
        var settings = new WorkspaceSettings(directory.toString(), "agenteam/sandbox-office:local", true,
            128, 20, 2000, 512, 1, 128, 180, "none");
        var runner = new DockerWorkspaceRunner(settings, docker,
            new SandboxRuntimeSettings(64, 1, "", ""));
        Path source = directory.resolve("source");
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(source.resolve(area));
        }
        for (String script : List.of("browser-smoke.cjs", "browser-memory.cjs")) {
            try (var input = getClass().getResourceAsStream("/browser/" + script)) {
                Files.copy(input, source.resolve("work/" + script));
            }
        }
        try (var input = getClass().getResourceAsStream("/sandbox/command_runner.py")) {
            Files.copy(input, source.resolve("work/command_runner.py"));
        }
        Path output = directory.resolve("output");
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = runner.execute(source, output, directory, "browser-read",
                "node browser-smoke.cjs && node browser-memory.cjs", "work", Duration.ofSeconds(150), control);
            var stdout = Files.readString(output.resolve(result.stdoutPath()));
            assertEquals(0, result.exitCode(), stdout + "\n" + Files.readString(output.resolve(result.stderrPath())));
            assertTrue(stdout.contains("浏览器动态正文、跳转、续读、拒绝、超时及 Git HTTP 克隆检查通过"));
            assertTrue(stdout.contains("浏览器内存、连续读取、超时、取消与超大网页恢复检查通过"));
        }
    }

    @AfterEach
    void cleanIncompleteTestResources() {
        if (Files.isRegularFile(directory.resolve("sandbox.pending"))) {
            docker.removeForWorkspace(Utf8Text.revision("workspace", directory.toAbsolutePath().normalize().toString()));
        }
    }
}
