package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands.remaining;

/**
 * 每次命令使用独立容器和有限容量的临时文件系统，主机上的已保存工作文件始终只读。
 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "docker", matchIfMissing = true)
public class DockerWorkspaceRunner implements SandboxExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(DockerWorkspaceRunner.class);
    private final WorkspaceSettings settings;
    private final DockerWorkspaceCommands docker;
    private final SandboxRuntimeSettings runtime;
    private final SandboxCapacity capacity;

    public DockerWorkspaceRunner(WorkspaceSettings settings, DockerWorkspaceCommands docker) {
        this(settings, docker, new SandboxRuntimeSettings(64, 2, "", ""));
    }

    public DockerWorkspaceRunner(WorkspaceSettings settings, DockerWorkspaceCommands docker,
                                 SandboxRuntimeSettings runtime) {
        this(settings, docker, runtime, new SandboxCapacity(runtime.maximumConcurrent()));
    }

    @Autowired
    public DockerWorkspaceRunner(WorkspaceSettings settings, DockerWorkspaceCommands docker,
                                 SandboxRuntimeSettings runtime, SandboxCapacity capacity) {
        this.settings = settings;
        this.docker = docker;
        this.runtime = runtime;
        this.capacity = capacity;
    }

    @Override
    public Result execute(Path source, Path destination, Path privateDirectory, String callId, String command,
                          String workingDirectory,
                          Duration timeout, ToolCallControl control) {
        try (var permit = capacity.acquire(timeout, control)) {
            return executeCommand(source, destination, privateDirectory, callId, command, workingDirectory,
                permit.remaining(), control);
        }
    }

    private Result executeCommand(Path source, Path destination, Path privateDirectory, String callId, String command,
                                  String workingDirectory,
                                  Duration timeout, ToolCallControl control) {
        if (!settings.executeEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_DISABLED", "当前部署未启用沙盒执行。");
        }
        if (!callId.matches("[A-Za-z0-9_-]{1,100}") || command == null || command.isBlank() || Utf8Text.size(
            command) > 64 * 1024 || command.indexOf('\0') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SANDBOX_COMMAND_INVALID",
                "请提供有效的命令，命令内容不能超过 64 KB。");
        }
        String directory = WorkspacePaths.logical(workingDirectory == null ? "work" : workingDirectory);
        if (!directory.isEmpty() && !directory.equals("work") && !directory.startsWith("work/")
            && !directory.equals("outputs") && !directory.startsWith("outputs/")) {
            throw WorkspacePaths.invalid();
        }
        String scope = Utf8Text.revision("workspace", privateDirectory.toAbsolutePath().normalize().toString());
        long timeoutNanos = Math.min(timeout.toNanos(), Duration.ofSeconds(settings.timeoutSeconds()).toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        // 为冻结进程和取回输出留出时间，命令超时后仍能把实际结果交给助手。
        long commandDeadline = deadline - Math.min(Duration.ofSeconds(10).toNanos(), timeoutNanos / 2);
        docker.removeForWorkspace(scope, remaining(commandDeadline), control);
        control.requireActive();
        String volume = "agenteam-workspace-" + UUID.randomUUID();
        Path scripts;
        try {
            WorkspacePaths.requireInside(privateDirectory, source);
            WorkspacePaths.requireInside(privateDirectory, destination);
            scripts = Files.createTempDirectory(privateDirectory, "command-");
            Files.writeString(scripts.resolve("command.sh"), command, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
        String container = "agenteam-sandbox-" + UUID.randomUUID();
        var flags = new ArrayList<>(
            List.of("--read-only", "--user=65534:65534", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--init",
                "--pids-limit=" + settings.processLimit(), "--memory-swap=" + settings.memoryMb() * 1024L * 1024,
                "--ulimit=fsize=" + settings.fileBytes() + ":" + settings.fileBytes(),
                "--mount=type=volume,src=" + volume + ",dst=/workspace,volume-nocopy",
                "--tmpfs=/tmp:rw,nosuid,nodev,size=" + runtime.temporaryMb() + "m,mode=1777",
                "--label=com.stonewu.agenteam.managed=true",
                "--label=com.stonewu.agenteam.workspace=" + scope));
        mount(flags, source, "/agenteam-saved");
        mount(flags, source.resolve("inputs"), "/workspace/inputs");
        mount(flags, source.resolve("tool-results"), "/workspace/tool-results");
        mount(flags, scripts, "/agenteam-command");
        String readerName = "agenteam-sandbox-" + UUID.randomUUID();
        var readerFlags = List.of("--read-only", "--user=65534:65534", "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--pids-limit=16", "--memory-swap=134217728", "--label=com.stonewu.agenteam.managed=true",
            "--label=com.stonewu.agenteam.workspace=" + scope,
            "--mount=type=volume,src=" + volume + ",dst=/workspace,volume-nocopy,readonly");
        var removed = new AtomicBoolean();
        control.track(() -> {
            if (removed.get()) {
                return;
            }
            long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            removeContainer(container, callId, cleanupDeadline);
            removeContainer(readerName, callId, cleanupDeadline);
        });
        String output = "work/command-results/" + callId;
        try {
            Files.writeString(privateDirectory.resolve("sandbox.pending"), scope, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            docker.require(List.of("volume", "create", "--driver=local", "--opt=type=tmpfs", "--opt=device=tmpfs",
                    "--opt=o=rw,nosuid,nodev,size=" + settings.workspaceMb() + "m,uid=65534,gid=65534,mode=0700,nr_inodes=" + settings.maximumFiles() * 4,
                    "--label=com.stonewu.agenteam.managed=true", "--label=com.stonewu.agenteam.workspace=" + scope, volume),
                remaining(commandDeadline), control);
            Files.writeString(scripts.resolve("launch.sh"),
                "#!/bin/sh\ncd " + quote("/workspace/" + directory) + " || exit 125\nsh /agenteam-command/command.sh > "
                    + quote("/workspace/" + output + "/stdout.txt") + " 2> " + quote(
                    "/workspace/" + output + "/stderr.txt") + "\n", StandardOpenOption.CREATE_NEW);
            SandboxMountPermissions.readable(privateDirectory, source, control);
            SandboxMountPermissions.readable(privateDirectory, scripts, control);
            docker.require(startup(container, settings.network(), settings.memoryMb(), settings.cpus(), flags),
                remaining(commandDeadline), control);
            control.requireActive();
            docker.require(exec(container,
                "mkdir -p /workspace/work /workspace/outputs && cp -R /agenteam-saved/work/. /workspace/work/ && cp -R /agenteam-saved/outputs/. /workspace/outputs/ && mkdir -p "
                    + quote("/workspace/" + output)), remaining(commandDeadline), control);
            int exit = 0;
            boolean timedOut = false;
            if (!settings.network().equals("none")) {
                control.beforeSend();
            }
            try {
                exit = docker.run(exec(container, "sh /agenteam-command/launch.sh"), remaining(commandDeadline),
                    control).exitCode();
            } catch (ApiException failure) {
                if (!failure.code().equals("SANDBOX_TIMEOUT") || !settings.network().equals("none")) {
                    throw failure;
                }
                exit = 124;
                timedOut = true;
                LOG.warn("沙盒命令超过允许时间，调用编号 {}", callId, failure);
            }
            control.requireActive();
            // 先冻结全部进程，再读取工作文件，防止后台进程在保存期间继续修改。
            docker.require(List.of("pause", container), remaining(deadline), control);
            // Docker 无法直接复制 tmpfs；只读辅助容器读取已冻结的共享临时卷。
            docker.require(startup(readerName, "none", 128, 1, readerFlags), remaining(deadline), control);
            control.requireActive();
            String[] available = docker.require(exec(readerName, "stat -f -c '%a %d' /workspace"), remaining(deadline),
                control).output().trim().split("\\s+");
            boolean capacityExceeded = available.length == 2 && (available[0].equals("0") || available[1].equals("0"));
            docker.extract(readerName, "work", destination, settings, control, remaining(deadline));
            docker.extract(readerName, "outputs", destination, settings, control, remaining(deadline));
            new WorkspaceFilesystem(destination, settings, () -> {
                control.requireActive();
                remaining(deadline);
            }, false).validate();
            return new Result(exit, timedOut, capacityExceeded, output + "/stdout.txt", output + "/stderr.txt");
        } catch (Exception failure) {
            control.requireActive();
            if (failure instanceof ApiException known) {
                throw known;
            }
            throw DockerWorkspaceCommands.unavailable(failure);
        } finally {
            cleanup(privateDirectory, scripts, container, readerName, volume, callId);
            removed.set(true);
        }
    }

    private List<String> startup(String name, String network, int memoryMb, int cpus, List<String> flags) {
        var arguments = new ArrayList<>(List.of("run", "--detach", "--name", name, "--workdir=/workspace",
            "--network=" + network, "--memory=" + memoryMb * 1024L * 1024, "--cpus=" + cpus,
            "--env=HOME=/tmp", "--env=LANG=C.UTF-8"));
        for (String proxy : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
            "all_proxy", "no_proxy")) {
            arguments.add("--env=" + proxy + "=");
        }
        arguments.addAll(flags);
        arguments.addAll(List.of(settings.image(), "sh", "-c", "while :; do sleep 3600; done"));
        return arguments;
    }

    private static List<String> exec(String container, String command) {
        return List.of("exec", "--workdir=/workspace", container, "sh", "-c", command);
    }

    private boolean removeContainer(String container, String callId, long deadline) {
        try {
            docker.remove(container, remaining(deadline));
            return true;
        } catch (RuntimeException failure) {
            LOG.warn("沙盒容器清理未完成，将根据保留记录继续清理，调用编号 {}", callId, failure);
            return false;
        }
    }

    private void cleanup(Path directory, Path scripts, String container, String reader, String volume, String callId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        boolean cleaned = removeContainer(container, callId, deadline);
        cleaned = removeContainer(reader, callId, deadline) && cleaned;
        try {
            docker.removeVolume(volume, remaining(deadline));
        } catch (RuntimeException failure) {
            cleaned = false;
            LOG.warn("沙盒临时卷清理未完成，将根据保留记录继续清理，调用编号 {}", callId, failure);
        }
        if (cleaned) {
            try {
                Files.deleteIfExists(directory.resolve("sandbox.pending"));
            } catch (IOException failure) {
                LOG.warn("沙盒清理记录删除失败，调用编号 {}", callId, failure);
            }
        }
        try {
            WorkspaceStore.deleteTree(directory, scripts);
        } catch (IOException failure) {
            LOG.warn("沙盒命令临时文件清理失败，调用编号 {}", callId, failure);
        }
    }

    private void mount(List<String> arguments, Path source, String destination) {
        if (source.toString().contains(",")) {
            throw WorkspacePaths.invalid();
        }
        arguments.add("--mount");
        arguments.add("type=bind,src=" + runtime.hostPath(source) + ",dst=" + destination + ",readonly");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
