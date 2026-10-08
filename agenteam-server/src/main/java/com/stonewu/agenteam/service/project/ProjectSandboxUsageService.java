package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.mapper.execution.RunJobMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.SandboxRunUsage;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只有实际使用过容器的父任务登记占用，模型思考和子任务执行期间随领取资格续期。
 */
@Service
public class ProjectSandboxUsageService {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectSandboxUsageService.class);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);
    private final Map<String, JobLease> active = new ConcurrentHashMap<>();
    private final ProjectWorkspaceLayout layout;
    private final UserSandboxRegistry registry;
    private final RunJobMapper jobs;
    private final SandboxLifecycleSettings settings;
    private final Clock clock;

    public ProjectSandboxUsageService(ProjectWorkspaceLayout layout, UserSandboxRegistry registry, RunJobMapper jobs,
                                      SandboxLifecycleSettings settings, Clock clock) {
        this.layout = layout;
        this.registry = registry;
        this.jobs = jobs;
        this.settings = settings;
        this.clock = clock;
    }

    public void started(JobLease lease) {
        active.put(key(lease.runId(), lease.version()), lease);
    }

    public void touch(RunRecord run, String session, ProjectLocation project, Duration timeout,
                      ToolCallControl control) {
        JobLease lease = active.get(key(run.id(), run.leaseVersion()));
        if (lease == null) {
            throw new ExecutionStoppedException();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try (var lock = UserWorkspaceLock.runtime(layout, project.workspaceId(),
                DockerWorkspaceCommands.remaining(deadline), control)) {
                var state = registry.state(project.workspaceId());
                if (state == null || !"stopping".equals(state.status())) {
                    control.requireActive();
                    if (!active.containsKey(key(run.id(), run.leaseVersion()))) {
                        throw new ExecutionStoppedException();
                    }
                    registry.save(
                        new SandboxRunUsage(run.id(), run.leaseVersion(), session, project, lease.id(), lease.owner(),
                            clock.millis()));
                    registry.markActivity(project.workspaceId(), clock.millis());
                    return;
                }
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
            control.pause(Duration.ofMillis(50));
        }
    }

    public void renew(JobLease lease) {
        update(lease, false);
    }

    public void release(JobLease lease) {
        active.remove(key(lease.runId(), lease.version()));
        update(lease, true);
    }

    private void update(JobLease lease, boolean release) {
        String workspace = ProjectPaths.workspaceId(lease.enterpriseId(), lease.userId());
        if (!Files.isDirectory(layout.control(workspace))) {
            return;
        }
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, workspace, LOCK_TIMEOUT, control)) {
            for (var usage : registry.usages(workspace)) {
                if (usage.runId().equals(lease.runId()) && usage.leaseVersion() == lease.version() && lease.owner()
                    .equals(usage.owner())) {
                    if (release) {
                        registry.remove(usage);
                    } else {
                        registry.save(
                            new SandboxRunUsage(usage.runId(), usage.leaseVersion(), usage.session(), usage.project(),
                                usage.jobId(), usage.owner(), clock.millis()));
                    }
                    registry.markActivity(workspace, clock.millis());
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("更新沙盒任务使用登记失败，执行编号 {}，工作编号 {}", lease.runId(), lease.id(), failure);
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void reconcile() {
        try {
            for (String workspace : registry.workspaces()) {
                try {
                    reconcile(workspace);
                } catch (IOException | RuntimeException failure) {
                    LOG.warn("核实沙盒任务使用登记失败，工作空间编号 {}", workspace, failure);
                }
            }
        } catch (IOException failure) {
            LOG.warn("读取沙盒任务登记目录失败，任务编号 sandbox-usage-reconcile", failure);
        }
    }

    private void reconcile(String workspace) throws IOException {
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, workspace, LOCK_TIMEOUT, control)) {
            for (var usage : registry.usages(workspace)) {
                if (clock.millis() - usage.updatedAt() < settings.staleSeconds() * 1000L) {
                    continue;
                }
                var lease = new JobLease(usage.jobId(), usage.project().enterpriseId(), usage.project().userId(),
                    usage.runId(),
                    usage.owner(), usage.leaseVersion(), Instant.EPOCH);
                // 必须核对数据库的真实领取资格；仍在容器内运行的命令另有持久记录保护。
                if (!jobs.valid(lease, clock.instant(), false)) {
                    registry.remove(usage);
                    registry.markActivity(workspace, clock.millis());
                }
            }
        }
    }

    private String key(String run, long version) {
        return run + ":" + version;
    }
}
