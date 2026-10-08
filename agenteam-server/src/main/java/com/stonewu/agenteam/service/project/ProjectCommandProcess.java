package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.project.entity.ProjectCommandRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 命令脚本互不覆盖，取消信号只交给对应的命令管理进程。
 */
@Component
public class ProjectCommandProcess {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String state, Integer exitCode, boolean timedOut, String error) {
        public boolean completed() {
            return "completed".equals(state);
        }
    }

    private final ProjectWorkspaceLayout layout;
    private final DockerWorkspaceCommands docker;
    private final ObjectMapper json;

    public ProjectCommandProcess(ProjectWorkspaceLayout layout, DockerWorkspaceCommands docker, ObjectMapper json) {
        this.layout = layout;
        this.docker = docker;
        this.json = json;
    }

    public void prepare(ProjectLocation project, String call, String command) throws IOException {
        Path scripts = layout.control(project.workspaceId()).resolve("command");
        Files.createDirectories(scripts);
        WorkspacePaths.requireInside(layout.root(), scripts);
        Path runner = scripts.resolve("runner.py");
        try (var source = getClass().getResourceAsStream("/sandbox/command_runner.py")) {
            if (source == null) {
                throw new IOException("沙盒命令管理程序缺失");
            }
            byte[] content = source.readAllBytes();
            if (!Files.exists(runner, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(content,
                Files.readAllBytes(runner))) {
                Path pending = scripts.resolve("runner-" + UUID.randomUUID() + ".tmp");
                try {
                    Files.write(pending, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    Files.move(pending, runner, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(pending);
                }
            }
        }
        Path script = scripts.resolve(call + ".sh");
        if (!Files.exists(script, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(script, command, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        } else if (!Files.readString(script).equals(command)) {
            throw new IOException("同一命令编号对应的脚本内容不一致");
        }
        readable(scripts, true);
        readable(runner, false);
        readable(script, false);
        Path output = layout.project(project).resolve("work/command-results/" + call);
        WorkspacePaths.requireInside(layout.root(), output);
        Files.createDirectories(output);
        writable(output.getParent());
        writable(output);
    }

    public void run(ProjectCommandRecord record, String directory, Duration timeout, ToolCallControl control) {
        String output = ProjectExecutionPaths.projectDirectory(
            record.project().directory()) + "/work/command-results/" + record.callId();
        double seconds = Math.max(0.1, timeout.toMillis() / 1000.0 - 2);
        docker.require(
            List.of("exec", record.containerId(), "python3", "-u", "/agenteam-command/runner.py", "run", record.token(),
                "/agenteam-command/" + record.callId() + ".sh", directory, output + "/stdout.txt",
                output + "/stderr.txt", Double.toString(seconds)),
            timeout.plusSeconds(5), control);
    }

    public Status status(ProjectCommandRecord record, Duration timeout) {
        return action(record, "status", timeout);
    }

    public Status cancel(ProjectCommandRecord record, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Status current = action(record, "cancel", DockerWorkspaceCommands.remaining(deadline));
        try (var control = new ToolCallControl(() -> {
        })) {
            while ("running".equals(current.state())) {
                control.pause(Duration.ofMillis(50));
                current = status(record, DockerWorkspaceCommands.remaining(deadline));
            }
        }
        return current;
    }

    private Status action(ProjectCommandRecord record, String action, Duration timeout) {
        String output = docker.require(
            List.of("exec", record.containerId(), "python3", "/agenteam-command/runner.py", action, record.token()),
            timeout, null).output();
        try {
            return json.readValue(output, Status.class);
        } catch (IOException failure) {
            throw DockerWorkspaceCommands.unavailable(failure);
        }
    }

    private void readable(Path path, boolean directory) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwxr-xr-x" : "rw-r--r--"));
        }
    }

    private void writable(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }
}
