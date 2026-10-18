package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.environment.entity.EnvironmentTerminalState;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 主机仅启动固定的 Docker 命令，用户输入经标准输入送入容器内的伪终端。 */
@Component
public class EnvironmentTerminalProcess {
    private final ProjectWorkspaceLayout layout;

    public EnvironmentTerminalProcess(ProjectWorkspaceLayout layout) {
        this.layout = layout;
    }

    public Process start(ProjectLocation project, EnvironmentTerminalState state) throws IOException {
        var directory = layout.control(project.workspaceId()).resolve("command");
        Files.createDirectories(directory);
        for (String resource : new String[]{"command_runner.py", "terminal_runner.py"}) {
            try (var input = getClass().getResourceAsStream("/sandbox/" + resource)) {
                if (input == null) {
                    throw new IOException("缺少终端管理程序");
                }
                Files.copy(input, directory.resolve(resource.equals("command_runner.py") ? "runner.py" : resource),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return new ProcessBuilder("docker", "exec", "-i", state.containerId, "python3", "-u",
            "/agenteam-command/terminal_runner.py", String.valueOf(state.cols), String.valueOf(state.rows)).start();
    }
}
