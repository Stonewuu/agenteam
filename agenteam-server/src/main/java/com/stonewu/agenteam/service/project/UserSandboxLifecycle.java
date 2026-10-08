package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.model.project.entity.ProjectCommandRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.UserSandboxState;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 登记占用和回收检查共用短时间锁；外部停止动作只作用于已登记的容器实例。
 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "docker", matchIfMissing = true)
public class UserSandboxLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(UserSandboxLifecycle.class);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    private final ProjectWorkspaceLayout layout;
    private final UserSandboxRegistry registry;
    private final UserContainerRuntime containers;
    private final ProjectCommandProcess processes;
    private final SandboxLifecycleSettings settings;
    private final Clock clock;

    public UserSandboxLifecycle(ProjectWorkspaceLayout layout, UserSandboxRegistry registry,
                                UserContainerRuntime containers,
                                ProjectCommandProcess processes, SandboxLifecycleSettings settings, Clock clock) {
        this.layout = layout;
        this.registry = registry;
        this.containers = containers;
        this.processes = processes;
        this.settings = settings;
        this.clock = clock;
    }

    public ProjectCommandRecord begin(ProjectLocation project, String call, String hash, String command,
                                      Duration timeout, ToolCallControl control) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            UserSandboxState stopping = null;
            try (var lock = UserWorkspaceLock.runtime(layout, project.workspaceId(),
                DockerWorkspaceCommands.remaining(deadline), control)) {
                layout.requireRegistered(project);
                var previous = registry.command(project.workspaceId(), call);
                if (previous != null) {
                    if (!project.projectId().equals(previous.projectId()) || !hash.equals(previous.commandHash())) {
                        throw new ApiException(HttpStatus.CONFLICT, "PROJECT_COMMAND_CONFLICT",
                            "此调用编号已用于其他命令。");
                    }
                    if (previous.result() == null) {
                        throw new ApiException(HttpStatus.CONFLICT, "PROJECT_COMMAND_UNCONFIRMED",
                            "上次命令的结果尚无法确认，请先检查已有结果。");
                    }
                    return previous;
                }
                var state = registry.state(project.workspaceId());
                if (state != null && "stopping".equals(state.status())) {
                    stopping = state;
                } else {
                    String daemon = containers.daemon(DockerWorkspaceCommands.remaining(deadline), control);
                    if (state != null && !daemon.equals(state.daemonId())) {
                        throw new ApiException(HttpStatus.CONFLICT, "PROJECT_EXECUTION_HOST_CHANGED",
                            "执行节点已变化，请先核实原节点的运行任务。");
                    }
                    processes.prepare(project, call, command);
                    String before = containers.ownedId(project, DockerWorkspaceCommands.remaining(deadline));
                    boolean running = before != null && containers.running(before,
                        DockerWorkspaceCommands.remaining(deadline));
                    if (state == null || !"running".equals(state.status()) || !running || !before.equals(
                        state.containerId())) {
                        containers.start(project, DockerWorkspaceCommands.remaining(deadline), control);
                    }
                    String id = running && state != null && "running".equals(state.status()) && before.equals(
                        state.containerId())
                        ? before : containers.ownedId(project, DockerWorkspaceCommands.remaining(deadline));
                    String generation = state != null && running && id.equals(
                        state.containerId()) ? state.generation() : UUID.randomUUID().toString();
                    long now = clock.millis();
                    registry.save(new UserSandboxState(project, id, generation, daemon, "running", 0, now));
                    var record = new ProjectCommandRecord(project.projectId(), hash, null, project, call, id,
                        generation,
                        UUID.randomUUID().toString(), now + DockerWorkspaceCommands.remaining(deadline).toMillis(), now,
                        "running");
                    registry.save(record);
                    layout.recordFileChange(project.workspaceId());
                    return record;
                }
            } catch (ApiException failure) {
                if ("SANDBOX_CAPACITY".equals(failure.code()) && reclaimIdle(project.workspaceId())) {
                    continue;
                }
                throw failure;
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
            finishStopping(stopping);
            control.requireActive();
        }
    }

    public void complete(ProjectCommandRecord record, SandboxExecutor.Result result) {
        change(record, result, "completed");
    }

    private void change(ProjectCommandRecord record, SandboxExecutor.Result result, String status) {
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, record.project().workspaceId(), CHECK_TIMEOUT, control)) {
            var current = registry.command(record.project().workspaceId(), record.callId());
            if (current == null || !record.token().equals(current.token()) || current.result() != null) {
                return;
            }
            registry.save(new ProjectCommandRecord(record.projectId(), record.commandHash(), result, record.project(),
                record.callId(),
                record.containerId(), record.generation(), record.token(), record.deadline(), clock.millis(), status));
            registry.markActivity(record.project().workspaceId(), clock.millis());
            layout.recordFileChange(record.project().workspaceId());
            if (result != null || "interrupted".equals(status)) {
                Files.deleteIfExists(
                    layout.control(record.project().workspaceId()).resolve("command").resolve(record.callId() + ".sh"));
            }
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    public SandboxExecutor.Result result(ProjectCommandRecord record, ProjectCommandProcess.Status status,
                                         boolean exceeded) {
        if (!status.completed() || status.exitCode() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_COMMAND_UNCONFIRMED",
                "命令执行结果尚无法确认，请先检查项目中的已有文件。");
        }
        String output = "work/command-results/" + record.callId();
        return new SandboxExecutor.Result(status.exitCode(), status.timedOut(), exceeded, output + "/stdout.txt",
            output + "/stderr.txt");
    }

    @Scheduled(fixedDelayString = "${execution.sandbox.idle-scan-seconds:30}000", initialDelayString = "${execution.sandbox.idle-scan-seconds:30}000")
    public void poll() {
        try {
            for (String workspace : registry.workspaces()) {
                try {
                    collect(workspace);
                } catch (IOException | RuntimeException failure) {
                    LOG.warn("检查沙盒空闲回收失败，工作空间编号 {}", workspace, failure);
                }
            }
        } catch (IOException failure) {
            LOG.warn("读取沙盒回收目录失败，任务编号 sandbox-idle-scan", failure);
        }
    }

    public boolean collect(String workspace) throws IOException {
        return collect(workspace, false);
    }

    private boolean collect(String workspace, boolean pressure) throws IOException {
        reconcileCommands(workspace);
        UserSandboxState selected;
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, workspace, CHECK_TIMEOUT, control)) {
            selected = registry.state(workspace);
            if (selected == null || "stopped".equals(selected.status())) {
                return false;
            }
            if (!"stopping".equals(selected.status())) {
                if (!registry.active(workspace).isEmpty() || !registry.usages(workspace).isEmpty()) {
                    return false;
                }
                if (selected.idleSince() == 0) {
                    registry.markActivity(workspace, clock.millis());
                    return false;
                }
                if (!pressure && clock.millis() - selected.idleSince() < settings.idleSeconds() * 1000L) {
                    return false;
                }
                selected = new UserSandboxState(selected.project(), selected.containerId(), selected.generation(),
                    selected.daemonId(),
                    "stopping", selected.idleSince(), clock.millis());
                registry.save(selected);
            }
        }
        finishStopping(selected);
        return true;
    }

    private boolean reclaimIdle(String requesting) {
        try {
            var candidates = new ArrayList<UserSandboxState>();
            for (String workspace : registry.workspaces()) {
                var state = registry.state(workspace);
                if (!workspace.equals(requesting) && state != null && "running".equals(
                    state.status()) && state.idleSince() > 0) {
                    candidates.add(state);
                }
            }
            candidates.sort(Comparator.comparingLong(UserSandboxState::idleSince));
            for (var state : candidates) {
                if (collect(state.project().workspaceId(), true)) {
                    return true;
                }
            }
            return false;
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private void reconcileCommands(String workspace) throws IOException {
        List<ProjectCommandRecord> active;
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, workspace, CHECK_TIMEOUT, control)) {
            active = registry.active(workspace);
        }
        for (var record : active) {
            String daemon = containers.daemon(CHECK_TIMEOUT, null);
            UserSandboxState state = registry.state(workspace);
            if (state == null || !daemon.equals(state.daemonId())) {
                throw new IOException("原执行节点尚未确认，不回收运行记录");
            }
            if (!containers.running(record.containerId(), CHECK_TIMEOUT)) {
                change(record, null, "interrupted");
                continue;
            }
            var status = processes.status(record, CHECK_TIMEOUT);
            if (status.completed()) {
                complete(record, result(record, status, false));
            } else if (("missing".equals(
                status.state()) && clock.millis() - record.updatedAt() >= settings.staleSeconds() * 1000L)
                || clock.millis() > record.deadline() + 5000) {
                boolean missing = "missing".equals(status.state());
                status = processes.cancel(record, CHECK_TIMEOUT);
                if (status.completed()) {
                    if (missing) {
                        // 阻止延迟到达的启动，但不能把丢失的旧结果猜成取消退出码。
                        change(record, null, "interrupted");
                    } else {
                        complete(record, result(record, status, false));
                    }
                } else {
                    throw new IOException("命令进程归属或清理结果尚未确认，调用编号 " + record.callId());
                }
            }
        }
    }

    private void finishStopping(UserSandboxState expected) {
        String workspace = expected.project().workspaceId();
        try (var control = new ToolCallControl(() -> {
        }); var transition = UserWorkspaceLock.transition(layout, workspace, CHECK_TIMEOUT, control)) {
            try (var lock = UserWorkspaceLock.runtime(layout, workspace, CHECK_TIMEOUT, control)) {
                var current = registry.state(workspace);
                if (current == null || !expected.generation().equals(current.generation()) || !"stopping".equals(
                    current.status())) {
                    return;
                }
                if (!registry.active(workspace).isEmpty() || !registry.usages(workspace).isEmpty()) {
                    registry.save(new UserSandboxState(current.project(), current.containerId(), current.generation(),
                        current.daemonId(),
                        "running", clock.millis(), clock.millis()));
                    return;
                }
            }
            if (!expected.daemonId().equals(containers.daemon(CHECK_TIMEOUT, null))) {
                throw new IOException("容器所属执行节点已变化，停止操作已取消");
            }
            containers.stopInstance(expected.project(), expected.containerId(), settings.stopSeconds(),
                Duration.ofSeconds(settings.stopSeconds() + 15L));
            try (var lock = UserWorkspaceLock.runtime(layout, workspace, CHECK_TIMEOUT, control)) {
                var current = registry.state(workspace);
                if (current != null && expected.generation().equals(current.generation()) && "stopping".equals(
                    current.status())) {
                    registry.save(new UserSandboxState(current.project(), current.containerId(), current.generation(),
                        current.daemonId(),
                        "stopped", current.idleSince(), clock.millis()));
                }
            }
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }
}
