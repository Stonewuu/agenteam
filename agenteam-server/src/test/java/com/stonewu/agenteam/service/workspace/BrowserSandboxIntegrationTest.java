package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在与生产一致的只读、非特权容器中验证网页脚本执行和代码拉取。 */
class BrowserSandboxIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void readsDynamicPagesAndClonesGitOverHttpInsideSandbox() throws Exception {
        check("browser-smoke.cjs");
    }

    @Test
    void releasesBrowserMemoryAfterSuccessTimeoutCancellationAndOversizedPage() throws Exception {
        check("browser-memory.cjs");
    }

    private void check(String script) throws Exception {
        var settings = new WorkspaceSettings(directory.toString(), "agenteam/sandbox-office:local", true,
            128, 20, 2000, 512, 1, 128, 180, "none");
        var runner = new DockerWorkspaceRunner(settings, new DockerWorkspaceCommands(),
            new SandboxRuntimeSettings(64, 1, "", ""));
        Path source = directory.resolve("source");
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(source.resolve(area));
        }
        try (var input = getClass().getResourceAsStream("/browser/" + script)) {
            Files.copy(input, source.resolve("work/" + script));
        }
        try (var input = getClass().getResourceAsStream("/sandbox/command_runner.py")) {
            Files.copy(input, source.resolve("work/command_runner.py"));
        }
        Path output = directory.resolve("output");
        try (var control = new ToolCallControl(() -> {
        })) {
            var result = runner.execute(source, output, directory, "browser-read",
                "node " + script, "work", Duration.ofSeconds(150), control);
            assertEquals(0, result.exitCode(), Files.readString(output.resolve(result.stderrPath())));
            assertTrue(Files.readString(output.resolve(result.stdoutPath())).contains("检查通过"));
        }
    }
}
