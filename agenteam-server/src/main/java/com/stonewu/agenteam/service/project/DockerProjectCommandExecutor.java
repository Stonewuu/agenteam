package com.stonewu.agenteam.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectCommandRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.SandboxCapacity;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands.remaining;

/**
 * 命令独立运行与清理，用户容器在所有任务退出并达到空闲期限后回收。
 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "docker", matchIfMissing = true)
public class DockerProjectCommandExecutor implements ProjectCommandExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(DockerProjectCommandExecutor.class);
    private final WorkspaceSettings settings;
    private final ProjectWorkspaceLayout layout;
    private final UserContainerRuntime containers;
    private final SandboxCapacity capacity;
    private final ProjectCommandProcess processes;
    private final UserSandboxLifecycle lifecycle;

    private record Pending(ProjectLocation project, String daemonId, String containerName) {
    }

    public DockerProjectCommandExecutor(WorkspaceSettings settings, SandboxRuntimeSettings runtime,
                                        ProjectWorkspaceLayout layout,
                                        UserContainerRuntime containers, DockerWorkspaceCommands docker) {
        this(settings, layout, containers, docker, new SandboxCapacity(runtime.maximumConcurrent()));
    }

    public DockerProjectCommandExecutor(WorkspaceSettings settings, ProjectWorkspaceLayout layout,
                                        UserContainerRuntime containers, DockerWorkspaceCommands docker,
                                        SandboxCapacity capacity) {
        this(settings, layout, containers, capacity, new ProjectCommandProcess(layout, docker, new ObjectMapper()));
    }

    private DockerProjectCommandExecutor(WorkspaceSettings settings, ProjectWorkspaceLayout layout,
                                         UserContainerRuntime containers,
                                         SandboxCapacity capacity, ProjectCommandProcess processes) {
        this(settings, layout, containers, capacity, processes,
            new UserSandboxLifecycle(layout, new UserSandboxRegistry(layout), containers, processes,
                new SandboxLifecycleSettings(900, 30, 60, 10), Clock.systemUTC()));
    }

    @Autowired
    public DockerProjectCommandExecutor(WorkspaceSettings settings, ProjectWorkspaceLayout layout,
                                        UserContainerRuntime containers,
                                        SandboxCapacity capacity, ProjectCommandProcess processes,
                                        UserSandboxLifecycle lifecycle) {
        this.settings = settings;
        this.layout = layout;
        this.containers = containers;
        this.capacity = capacity;
        this.processes = processes;
        this.lifecycle = lifecycle;
    }

    @Override
    public SandboxExecutor.Result execute(ProjectLocation location, String callId, String command,
                                          String workingDirectory,
                                          Duration timeout, ToolCallControl control) {
        if (!settings.executeEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_DISABLED", "当前部署未启用沙盒执行。");
        }
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,100}") || command == null || command.isBlank()
            || Utf8Text.size(command) > 65536 || command.indexOf('\0') >= 0) {
            throw ApiException.invalidField("command", "请提供有效的命令，命令内容不能超过 64 KB。");
        }
        long deadline = System.nanoTime() + Math.min(timeout.toNanos(),
            Duration.ofSeconds(settings.timeoutSeconds()).toNanos());
        try {
            layout.requireRegistered(location);
            if (Files.exists(layout.control(location.workspaceId()).resolve("container.pending"),
                LinkOption.NOFOLLOW_LINKS)) {
                recover(location, remaining(deadline), control);
            }
            var working = ProjectExecutionPaths.workingDirectory(layout, location, workingDirectory);
            if (!Files.isDirectory(working, LinkOption.NOFOLLOW_LINKS)) {
                throw ApiException.invalidField("workingDirectory", "指定的工作目录不存在，请先创建目录。");
            }
            String directory = ProjectExecutionPaths.containerDirectory(layout, location, working);
            try (var permit = capacity.acquire(remaining(deadline), control)) {
                var record = lifecycle.begin(location, callId, Utf8Text.revision(directory, command), command,
                    remaining(deadline), control);
                if (record.result() != null) {
                    return record.result();
                }
                return run(record, directory, deadline, control);
            }
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private SandboxExecutor.Result run(ProjectCommandRecord record, String directory, long deadline,
                                       ToolCallControl control) throws IOException {
        var done = new AtomicBoolean();
        var monitorStopped = new AtomicBoolean();
        var exceeded = new AtomicBoolean();
        var monitor = new ProjectCapacityMonitor(layout.files(record.project().workspaceId()), settings,
            monitorStopped);
        var baseline = monitor.usage();
        var watchdog = Executors.newSingleThreadScheduledExecutor();
        var cleanup = new Object();
        control.track(() -> {
            synchronized (cleanup) {
                if (!done.get()) {
                    cancelSafely(record);
                }
            }
        });
        boolean complete = false;
        try {
            watchdog.scheduleWithFixedDelay(() -> {
                try {
                    var usage = monitor.usage();
                    if (!done.get() && monitor.exceeded(usage) && usage.increasedFrom(baseline)) {
                        exceeded.set(true);
                        cancelSafely(record);
                    }
                } catch (IOException | RuntimeException failure) {
                    LOG.warn("检查项目容量失败，项目编号 {}，调用编号 {}", record.projectId(), record.callId(), failure);
                    cancelSafely(record);
                }
            }, 500, 500, TimeUnit.MILLISECONDS);
            control.beforeSend();
            processes.run(record, directory, remaining(deadline), control);
            var status = processes.status(record, Duration.ofSeconds(10));
            var result = lifecycle.result(record, status, exceeded.get() || monitor.exceeded(monitor.usage()));
            lifecycle.complete(record, result);
            complete = true;
            return result;
        } finally {
            monitorStopped.set(true);
            watchdog.shutdownNow();
            synchronized (cleanup) {
                if (!complete) {
                    cancelSafely(record);
                }
                done.set(true);
            }
        }
    }

    private void cancelSafely(ProjectCommandRecord record) {
        boolean interrupted = Thread.interrupted();
        try {
            var status = processes.cancel(record, Duration.ofSeconds(10));
            if (status.completed()) {
                lifecycle.complete(record, lifecycle.result(record, status, false));
            }
        } catch (RuntimeException failure) {
            LOG.warn("命令停止结果待核实，项目编号 {}，调用编号 {}", record.projectId(), record.callId(), failure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void recover(ProjectLocation location, Duration timeout, ToolCallControl control) {
        layout.project(location);
        try (var lock = UserWorkspaceLock.acquire(layout, location.workspaceId(), timeout, control)) {
            var pending = layout.control(location.workspaceId()).resolve("container.pending");
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            var previous = layout.read(pending, Pending.class);
            if (previous.project() == null || !location.workspaceId().equals(previous.project().workspaceId())) {
                throw ProjectWorkspaceLayout.unavailable();
            }
            if (!containers.daemon(Duration.ofSeconds(5), null).equals(previous.daemonId())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_EXECUTION_HOST_CHANGED",
                    "请先在原执行节点核实旧命令，再切换执行节点。");
            }
            if (previous.containerName() == null) {
                containers.requireLegacyStopped(previous.project(), Duration.ofSeconds(5));
            } else {
                if (!containers.name(previous.project()).equals(previous.containerName())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_EXECUTION_HOST_CHANGED",
                        "旧命令容器不属于当前部署，请先核实原执行环境。");
                }
                // 仅兼容升级前未结束的记录，新命令不再生成此文件。
                containers.stop(previous.project(), Duration.ofSeconds(5));
            }
            layout.recordFileChange(location.workspaceId());
            Files.delete(pending);
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }
}
