package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.UserContainerOwnership;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 只调用固定 Docker 管理命令；智能体的命令正文从不传给主机命令解释器。
 */
@Component
public class DockerWorkspaceCommands implements SandboxContainerCleanup {
    private final UserWorkspaceSettings userWorkspaces;

    public DockerWorkspaceCommands() {
        this(new UserWorkspaceSettings(".agenteam/user-workspaces", ""));
    }

    @Autowired
    public DockerWorkspaceCommands(UserWorkspaceSettings userWorkspaces) {
        this.userWorkspaces = userWorkspaces;
    }

    public record Result(int exitCode, String output) {
    }

    public Result run(List<String> arguments, Duration timeout) {
        return run(arguments, timeout, null);
    }

    public Result run(List<String> arguments, Duration timeout, ToolCallControl control) {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(arguments);
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        var threads = Executors.newVirtualThreadPerTaskExecutor();
        try {
            if (control != null) {
                control.requireActive();
            }
            remaining(deadline);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var active = process;
            if (control != null) {
                control.track(() -> active.destroyForcibly());
            }
            var reading = threads.submit(() -> drain(active.getInputStream()));
            if (!process.waitFor(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                throw new TimeoutException("沙盒操作超过本次允许时间");
            }
            if (control != null) {
                control.requireActive();
            }
            return new Result(process.exitValue(), reading.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS));
        } catch (Exception cause) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (control != null) {
                control.requireActive();
            }
            if (cause instanceof ApiException known) {
                throw known;
            }
            if (cause instanceof TimeoutException) {
                throw timedOut(cause);
            }
            throw unavailable(cause);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            threads.shutdownNow();
        }
    }

    public void require(List<String> arguments) {
        require(arguments, Duration.ofSeconds(15), null);
    }

    public Result require(List<String> arguments, Duration timeout, ToolCallControl control) {
        var result = run(arguments, timeout, control);
        if (result.exitCode() != 0) {
            throw unavailable(
                new IOException("Docker 管理命令执行失败，退出码 " + result.exitCode() + "：" + result.output()));
        }
        return result;
    }

    public void remove(String container) {
        remove(container, Duration.ofSeconds(10));
    }

    public void remove(String container, Duration timeout) {
        requireContainer(container);
        long deadline = System.nanoTime() + timeout.toNanos();
        var result = run(List.of("rm", "--force", container), remaining(deadline));
        if (result.exitCode() != 0 && result.output().contains("already in progress")) {
            while (true) {
                var inspection = run(List.of("inspect", "--type=container", "--format={{.Id}}", container),
                    remaining(deadline));
                if (inspection.exitCode() != 0 && (inspection.output().contains("No such object") || inspection.output()
                    .contains("No such container"))) {
                    return;
                }
                if (inspection.exitCode() != 0) {
                    throw unavailable(new IOException("无法确认容器清理结果：" + inspection.output()));
                }
                try {
                    Thread.sleep(Math.min(50, Math.max(1, remaining(deadline).toMillis())));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw unavailable(failure);
                }
            }
        }
        if (result.exitCode() != 0 && !result.output().contains("No such container")) {
            throw unavailable(new IOException("容器移除失败：" + result.output()));
        }
    }

    @Override
    public void removeUserContainer(String workspaceId, Duration timeout) {
        if (workspaceId == null || !workspaceId.matches("[0-9a-f]{64}")) {
            throw WorkspacePaths.invalid();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        String storage = userWorkspaces.hostPath(Path.of(userWorkspaces.root()).resolve(workspaceId).resolve("files"));
        String id = UserContainerOwnership.ownedId(this, storage, remaining(deadline), null);
        if (id != null) {
            remove(id, remaining(deadline));
        }
    }

    @Override
    public void removeForWorkspace(String scope) {
        removeForWorkspace(scope, Duration.ofSeconds(30), null);
    }

    @Override
    public void removeForWorkspace(String scope, Duration timeout, ToolCallControl control) {
        if (!scope.matches("[0-9a-f]{64}")) {
            throw WorkspacePaths.invalid();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        var result = run(List.of("ps", "-aq", "--filter", "label=com.stonewu.agenteam.workspace=" + scope),
            remaining(deadline), control);
        if (result.exitCode() != 0) {
            throw unavailable(new IOException("无法读取工作空间容器记录：" + result.output()));
        }
        for (String id : result.output().split("\\R")) {
            if (!id.isBlank()) {
                if (control != null) {
                    control.requireActive();
                }
                remove(id.trim(), remaining(deadline));
            }
        }
        var volumes = run(List.of("volume", "ls", "-q", "--filter", "label=com.stonewu.agenteam.workspace=" + scope),
            remaining(deadline), control);
        if (volumes.exitCode() != 0) {
            throw unavailable(new IOException("无法读取工作空间临时卷记录：" + volumes.output()));
        }
        for (String name : volumes.output().split("\\R")) {
            if (!name.isBlank()) {
                if (control != null) {
                    control.requireActive();
                }
                removeVolume(name.trim(), remaining(deadline));
            }
        }
    }

    public void removeVolume(String name) {
        removeVolume(name, Duration.ofSeconds(10));
    }

    public void removeVolume(String name, Duration timeout) {
        if (!name.matches("agenteam-workspace-[0-9a-f-]{36}")) {
            throw WorkspacePaths.invalid();
        }
        var result = run(List.of("volume", "rm", name), timeout);
        if (result.exitCode() != 0 && !result.output().contains("no such volume")) {
            throw unavailable(new IOException("临时工作卷移除失败：" + result.output()));
        }
    }

    public void extract(String container, String area, Path target, WorkspaceSettings settings,
                        ToolCallControl control) {
        extract(container, area, target, settings, control, Duration.ofSeconds(35));
    }

    public void extract(String container, String area, Path target, WorkspaceSettings settings, ToolCallControl control,
                        Duration timeout) {
        requireContainer(container);
        if (!List.of("work", "outputs").contains(area)) {
            throw WorkspacePaths.invalid();
        }
        Process process = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        var threads = Executors.newVirtualThreadPerTaskExecutor();
        try {
            control.requireActive();
            remaining(deadline);
            process = new ProcessBuilder("docker", "exec", container, "tar", "-cf", "-", "-C", "/workspace",
                area).start();
            var active = process;
            control.track(() -> active.destroyForcibly());
            var stderr = threads.submit(() -> drain(active.getErrorStream()));
            var extraction = threads.submit(() -> {
                WorkspaceArchive.extract(active.getInputStream(), target, area, settings, control);
                return true;
            });
            try {
                extraction.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                process.destroyForcibly();
                throw failure;
            }
            if (!process.waitFor(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("沙盒文件读取超过本次允许时间");
            }
            if (process.exitValue() != 0) {
                throw new IOException(
                    "容器文件无法完整读取：" + stderr.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS));
            }
        } catch (Exception cause) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            control.requireActive();
            if (cause.getCause() instanceof ApiException known) {
                throw known;
            }
            if (cause instanceof ApiException known) {
                throw known;
            }
            if (cause instanceof TimeoutException) {
                throw timedOut(cause);
            }
            throw unavailable(cause);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            threads.shutdownNow();
        }
    }

    private static String drain(InputStream input) throws IOException {
        try (input; var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (bytes.size() < 8192) {
                    bytes.write(buffer, 0, Math.min(read, 8192 - bytes.size()));
                }
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static void requireContainer(String id) {
        if (!id.matches("[0-9a-f]{12,64}|(?:agentscope|agenteam)-sandbox-[0-9a-f-]{36}|agenteam-user-[0-9a-f]{64}")) {
            throw WorkspacePaths.invalid();
        }
    }

    public static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_UNAVAILABLE",
            "沙盒暂时无法使用，请确认容器服务和执行镜像可用。", cause);
    }

    public static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw timedOut(new TimeoutException("沙盒本次操作的剩余时间已用完"));
        }
        return Duration.ofNanos(nanos);
    }

    private static ApiException timedOut(Throwable cause) {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SANDBOX_TIMEOUT",
            "本次沙盒操作超过允许时间，请缩小处理范围或改用其他方法。", cause);
    }
}
