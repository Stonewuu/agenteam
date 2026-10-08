package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands.remaining;

/**
 * 同一用户只保留一个容器并挂载完整用户文件空间；切换项目不改变挂载。
 */
@Component
public class UserContainerRuntime {
    private final WorkspaceSettings settings;
    private final SandboxRuntimeSettings runtime;
    private final ProjectWorkspaceLayout layout;
    private final DockerWorkspaceCommands docker;
    private final SandboxLifecycleSettings lifecycle;

    public UserContainerRuntime(WorkspaceSettings settings, SandboxRuntimeSettings runtime,
                                ProjectWorkspaceLayout layout, DockerWorkspaceCommands docker) {
        this(settings, runtime, layout, docker, new SandboxLifecycleSettings(900, 30, 60, 10));
    }

    @Autowired
    public UserContainerRuntime(WorkspaceSettings settings, SandboxRuntimeSettings runtime,
                                ProjectWorkspaceLayout layout,
                                DockerWorkspaceCommands docker, SandboxLifecycleSettings lifecycle) {
        this.settings = settings;
        this.runtime = runtime;
        this.layout = layout;
        this.docker = docker;
        this.lifecycle = lifecycle;
    }

    public String name(ProjectLocation location) {
        return UserContainerOwnership.name(storagePath(location));
    }

    public String daemon(Duration timeout, ToolCallControl control) {
        String id = docker.require(List.of("info", "--format={{.ID}}"), timeout, control).output().trim();
        if (id.isBlank()) {
            throw DockerWorkspaceCommands.unavailable(new IOException("无法识别当前 Docker 执行节点"));
        }
        return id;
    }

    public void start(ProjectLocation location, Duration timeout, ToolCallControl control) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (var lock = UserWorkspaceLock.capacity(layout, remaining(deadline), control)) {
            startLocked(location, remaining(deadline), control);
        }
    }

    private void startLocked(ProjectLocation location, Duration timeout, ToolCallControl control) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        layout.requireRegistered(location);
        Path scripts = layout.control(location.workspaceId()).resolve("command");
        Files.createDirectories(scripts);
        if (scripts.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(scripts, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        String image = docker.require(List.of("image", "inspect", "--format={{.Id}}", settings.image()),
            remaining(deadline), control).output().trim();
        var arguments = create(location, image);
        String signature = Utf8Text.revision("user-container", String.join("\n", arguments));
        String name = name(location);
        String existing = UserContainerOwnership.ownedId(docker, storagePath(location), remaining(deadline), control);
        if (existing != null) {
            var found = docker.require(
                List.of("inspect", "--type=container", "--format={{.Config.Labels.agenteam_user_configuration}}",
                    existing),
                remaining(deadline), control);
            if (!found.output().trim().equals(signature)) {
                if (running(existing, remaining(deadline))) {
                    throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_CONFIGURATION_CHANGED",
                        "执行环境配置已变化，请等待现有任务完成并回收后重试。");
                }
                docker.remove(existing, remaining(deadline));
                existing = null;
            }
        }
        // 只在确认当前名称没有被其他部署占用后记录，便于清理已停止的容器。
        layout.write(layout.control(location.workspaceId()).resolve("container.created"), Map.of("name", name));
        if (existing == null) {
            arguments.add(1, "--label=agenteam_user_configuration=" + signature);
            docker.require(arguments, remaining(deadline), control);
        }
        String id = UserContainerOwnership.ownedId(docker, storagePath(location), remaining(deadline), control);
        if (id == null) {
            throw DockerWorkspaceCommands.unavailable(new IOException("创建后未能确认用户容器归属"));
        }
        if (!running(id, remaining(deadline))) {
            String active = docker.require(
                List.of("ps", "-q", "--filter", "label=agenteam_user_root=" + deploymentScope()),
                remaining(deadline), control).output();
            if (active.lines().filter(value -> !value.isBlank()).count() >= lifecycle.maximumRunning()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SANDBOX_CAPACITY",
                    "执行环境正在被其他任务使用，请稍后重试。");
            }
            preparePermissions(location, deadline, control);
            docker.require(List.of("start", id), remaining(deadline), control);
        }
    }

    public String ownedId(ProjectLocation location, Duration timeout) {
        return UserContainerOwnership.ownedId(docker, storagePath(location), timeout, null);
    }

    public boolean running(String id, Duration timeout) {
        var result = docker.run(List.of("inspect", "--type=container", "--format={{.State.Running}}", id), timeout);
        if (result.exitCode() != 0 && result.output().contains("No such")) {
            return false;
        }
        if (result.exitCode() != 0) {
            throw DockerWorkspaceCommands.unavailable(new IOException("无法确认容器运行状态"));
        }
        return "true".equals(result.output().trim());
    }

    public void stopInstance(ProjectLocation project, String id, int graceSeconds, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String current = ownedId(project, remaining(deadline));
        if (current == null || !current.equals(id)) {
            return;
        }
        docker.require(List.of("stop", "--time=" + graceSeconds, id), remaining(deadline), null);
        if (running(id, remaining(deadline))) {
            throw DockerWorkspaceCommands.unavailable(new IOException("容器停止后仍在运行"));
        }
    }

    public void stop(ProjectLocation location, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String id = UserContainerOwnership.ownedId(docker, storagePath(location), remaining(deadline), null);
        if (id == null) {
            return;
        }
        var result = docker.run(List.of("stop", "--time=0", id), remaining(deadline));
        if (result.exitCode() != 0 && !result.output().contains("No such container")) {
            throw DockerWorkspaceCommands.unavailable(new IOException("用户容器停止失败：" + result.output()));
        }
        var state = docker.run(List.of("inspect", "--type=container", "--format={{.State.Running}}", id),
            remaining(deadline));
        if (state.exitCode() == 0 && state.output().trim().equals("false")) {
            try {
                preparePermissions(location, deadline, null);
            } catch (IOException failure) {
                throw DockerWorkspaceCommands.unavailable(failure);
            }
            return;
        }
        if (state.exitCode() != 0 && state.output().contains("No such")) {
            return;
        }
        throw DockerWorkspaceCommands.unavailable(new IOException("未能确认用户容器已经停止"));
    }

    public void requireLegacyStopped(ProjectLocation location, Duration timeout) {
        layout.workspace(location.workspaceId());
        var state = docker.run(List.of("inspect", "--type=container", "--format={{.State.Running}}",
            "agenteam-user-" + location.workspaceId()), timeout);
        if (state.exitCode() != 0 && state.output().contains("No such")) {
            return;
        }
        if (state.exitCode() == 0 && state.output().trim().equals("false")) {
            return;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_EXECUTION_HOST_CHANGED",
            "旧版命令容器尚未确认停止，请核对原运行环境并完成恢复；当前部署未操作该容器。");
    }

    private ArrayList<String> create(ProjectLocation location, String image) throws IOException {
        var arguments = new ArrayList<>(
            List.of("create", "--name=" + name(location), "--read-only", "--user=" + containerUser(location), "--init",
                "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=" + settings.processLimit(),
                "--memory=" + settings.memoryMb() * 1024L * 1024, "--memory-swap=" + settings.memoryMb() * 1024L * 1024,
                "--cpus=" + settings.cpus(), "--network=" + settings.network(), "--workdir=/workspace",
                "--ulimit=fsize=" + settings.fileBytes() + ":" + settings.fileBytes(),
                "--tmpfs=/tmp:rw,nosuid,nodev,size=" + runtime.temporaryMb() + "m,mode=1777",
                "--env=HOME=/tmp", "--env=LANG=C.UTF-8", "--label=com.stonewu.agenteam.managed=true",
                "--label=agenteam_user_root=" + deploymentScope(),
                "--label=com.stonewu.agenteam.user-workspace=" + location.workspaceId(),
                "--label=" + UserContainerOwnership.LABEL + "=" + UserContainerOwnership.scope(storagePath(location))));
        for (String proxy : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
            "all_proxy", "no_proxy")) {
            arguments.add("--env=" + proxy + "=");
        }
        mount(arguments, layout.files(location.workspaceId()), ProjectExecutionPaths.ROOT, false);
        mount(arguments, layout.control(location.workspaceId()).resolve("command"), "/agenteam-command", true);
        arguments.addAll(List.of(image, "sh", "-c", "while :; do sleep 3600; done"));
        return arguments;
    }

    private String containerUser(ProjectLocation location) throws IOException {
        Path owner = layout.workspace(location.workspaceId());
        if (owner.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            int uid = ((Number) Files.getAttribute(owner, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
            int gid = ((Number) Files.getAttribute(owner, "unix:gid", LinkOption.NOFOLLOW_LINKS)).intValue();
            // Linux 中沿用应用创建用户目录时的非管理员身份，避免生成文件归属另一个用户而无法编辑。
            if (uid > 0) {
                return uid + ":" + (gid > 0 ? gid : 65534);
            }
        }
        return "65534:65534";
    }

    private void mount(List<String> arguments, Path source, String destination, boolean readonly) {
        arguments.add("--mount=type=bind,src=" + layout.hostPath(
            source) + ",dst=" + destination + ",bind-recursive=disabled" + (readonly ? ",readonly" : ""));
    }

    private String storagePath(ProjectLocation location) {
        return layout.hostPath(layout.files(location.workspaceId()));
    }

    private String deploymentScope() {
        return UserContainerOwnership.scope(layout.hostPath(layout.root()));
    }

    private void preparePermissions(ProjectLocation location, long deadline,
                                    ToolCallControl control) throws IOException {
        Path files = layout.files(location.workspaceId());
        if (!files.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        var pending = new ArrayDeque<Path>();
        pending.add(files);
        while (!pending.isEmpty()) {
            remaining(deadline);
            if (control != null) {
                control.requireActive();
            }
            Path path = pending.removeFirst();
            if (Files.isSymbolicLink(path)) {
                continue;
            }
            WorkspacePaths.requireInside(layout.root(), path);
            boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            if (!directory && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            // 先恢复目录权限再进入子目录，命令中的 chmod 不应使用户文件永久无法读取。
            var permissions = Files.getPosixFilePermissions(path);
            var desired = PosixFilePermissions.fromString(
                directory || permissions.contains(PosixFilePermission.OWNER_EXECUTE) ? "rwxrwxrwx" : "rw-rw-rw-");
            if (!permissions.equals(desired)) {
                Files.setPosixFilePermissions(path, desired);
            }
            if (directory) {
                try (var children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        pending.addLast(child);
                    }
                }
            }
        }
    }
}
