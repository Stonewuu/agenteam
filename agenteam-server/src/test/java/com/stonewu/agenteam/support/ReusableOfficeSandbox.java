package com.stonewu.agenteam.support;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 正常办公功能共用一个测试容器，每次调用重新准备工作目录。
 * 仅供可信测试命令使用；生产沙箱的隔离、取消及容量限制由专门用例验证。
 */
public final class ReusableOfficeSandbox implements SandboxExecutor, AutoCloseable {
    private final Path workspace;
    private final Path scripts;
    private final GenericContainer<?> container;
    private final DockerWorkspaceCommands docker = new DockerWorkspaceCommands();
    private boolean closed;

    public ReusableOfficeSandbox(String image) throws IOException {
        Path root = Files.createDirectories(TestOutputPaths.path("office-containers", UUID.randomUUID().toString()));
        workspace = Files.createDirectory(root.resolve("workspace"));
        scripts = Files.createDirectory(root.resolve("scripts"));
        permissions(workspace, "rwxrwxrwx");
        permissions(scripts, "rwxr-xr-x");
        container = new GenericContainer<>(DockerImageName.parse(image))
            .withLabel("com.stonewu.agenteam.test", "office-functions")
            .withNetworkMode("none")
            .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=64m"))
            .withCreateContainerCmdModifier(command -> {
                command.withUser("65534:65534");
                // 使用 Docker 的目录挂载接口；实际文件处理同时验证挂载，无需再启动文件挂载探测容器。
                command.getHostConfig().withReadonlyRootfs(true).withCapDrop(Capability.ALL)
                    .withSecurityOpts(List.of("no-new-privileges"))
                    .withMounts(List.of(
                        new Mount().withType(MountType.BIND).withSource(MountableFile.forHostPath(workspace).getResolvedPath())
                            .withTarget("/workspace"),
                        new Mount().withType(MountType.BIND).withSource(MountableFile.forHostPath(scripts).getResolvedPath())
                            .withTarget("/verification").withReadOnly(true)))
                    .withMemory(512L * 1024 * 1024).withNanoCPUs(1_000_000_000L).withPidsLimit(128L);
            })
            .withCommand("sh", "-c", "while :; do sleep 3600; done");
    }

    public String containerId() {
        return container.getContainerId();
    }

    @Override
    public synchronized Result execute(Path source, Path destination, Path privateDirectory, String callId,
                                       String command, String workingDirectory, Duration timeout, ToolCallControl control) {
        if (closed) {
            throw new IllegalStateException("办公测试容器已经关闭");
        }
        if (!List.of("work", ".", "/workspace").contains(workingDirectory)) {
            throw new IllegalArgumentException("办公测试只使用固定工作目录");
        }
        try {
            control.requireActive();
            if (container.getContainerId() != null) {
                // 输出由容器用户创建，Linux 宿主机未必能删除其中的文件；由同一用户清理并保留挂载目录。
                docker.require(List.of("exec", container.getContainerId(), "find", "/workspace", "-mindepth", "1",
                    "-maxdepth", "1", "-exec", "rm", "-rf", "--", "{}", "+"), timeout, control);
            }
            copy(source, workspace, true);
            Files.createDirectories(workspace.resolve("work"));
            Files.createDirectories(workspace.resolve("outputs"));
            permissions(workspace.resolve("work"), "rwxrwxrwx");
            permissions(workspace.resolve("outputs"), "rwxrwxrwx");
            Files.writeString(scripts.resolve("command.sh"), command);
            permissions(scripts.resolve("command.sh"), "rw-r--r--");
            if (container.getContainerId() == null) {
                container.start();
            }
            String directory = workingDirectory.equals("work") ? "/workspace/work" : "/workspace";
            var result = docker.run(List.of("exec", "--workdir", directory, container.getContainerId(), "sh", "-c",
                "exec sh /verification/command.sh > /workspace/work/test.stdout 2> /workspace/work/test.stderr"), timeout, control);
            copy(workspace.resolve("work"), destination.resolve("work"), false);
            copy(workspace.resolve("outputs"), destination.resolve("outputs"), false);
            return new Result(result.exitCode(), false, false, "work/test.stdout", "work/test.stderr");
        } catch (IOException failure) {
            var wrapped = new UncheckedIOException("办公测试工作文件复制失败", failure);
            closeAfterFailure(wrapped);
            throw wrapped;
        } catch (RuntimeException failure) {
            // 命令超时或取消后不再复用可能仍有子进程的容器。
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private static void copy(Path source, Path destination, boolean writableInContainer) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("办公测试文件不应包含符号链接");
                }
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
                if (writableInContainer) {
                    permissions(target, Files.isDirectory(target) ? "rwxrwxrwx" : "rw-rw-rw-");
                }
            }
        }
    }

    private static void permissions(Path path, String value) throws IOException {
        // Linux 上容器用户与宿主机用户不同，只调整本辅助类创建的测试文件。
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(value));
        }
    }

    private void closeAfterFailure(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            container.stop();
        }
    }
}
