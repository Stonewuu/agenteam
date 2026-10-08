package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 项目目录是唯一文件位置，提交只保存来源记录，不用会话副本替换项目内容。
 */
@Component
public class ProjectWorkspaceStore {
    private final ProjectMetadataService projects;
    private final ConversationProjectService conversations;
    private final ProjectWorkspaceLayout layout;
    private final LegacyProjectMigration migration;
    private final ProjectCommandExecutor executor;
    private final WorkspaceSettings settings;
    private final ProjectInputFileService inputFiles;
    private final ProjectSandboxUsageService sandboxUsage;

    public ProjectWorkspaceStore(ProjectMetadataService projects, ConversationProjectService conversations,
                                 ProjectWorkspaceLayout layout,
                                 LegacyProjectMigration migration, ProjectCommandExecutor executor,
                                 WorkspaceSettings settings, ProjectInputFileService inputFiles) {
        this(projects, conversations, layout, migration, executor, settings, inputFiles, null);
    }

    @Autowired
    public ProjectWorkspaceStore(ProjectMetadataService projects, ConversationProjectService conversations,
                                 ProjectWorkspaceLayout layout,
                                 LegacyProjectMigration migration, ProjectCommandExecutor executor,
                                 WorkspaceSettings settings,
                                 ProjectInputFileService inputFiles, ProjectSandboxUsageService sandboxUsage) {
        this.projects = projects;
        this.conversations = conversations;
        this.layout = layout;
        this.migration = migration;
        this.executor = executor;
        this.settings = settings;
        this.inputFiles = inputFiles;
        this.sandboxUsage = sandboxUsage;
    }

    public WorkspaceSession open(RunRecord run, String session, ToolCallControl control, Duration timeout,
                                 Runnable requireLease) {
        requireLease.run();
        String projectId = run.executionConfig().path("workspaceProjectId").asText(null);
        var project = projectId == null ? conversations.ensure(run.enterpriseId(), run.userId(), run.conversationId())
            : projects.require(run.enterpriseId(), run.userId(), projectId);
        return acquire(project, run, session, control, timeout, requireLease);
    }

    public <T> T read(String enterprise, String user, String conversation,
                      Function<WorkspaceStore.Snapshot, T> action) {
        var project = conversations.ensure(enterprise, user, conversation);
        try (var control = new ToolCallControl(() -> {
        }); var scope = acquire(project, null, conversation, control, Duration.ofSeconds(30), () -> {
        })) {
            return action.apply(new WorkspaceStore.Snapshot(scope.manifest(), scope.current(), false));
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private Scope acquire(WorkspaceProjectRow project, RunRecord run, String session, ToolCallControl control,
                          Duration timeout, Runnable requireLease) {
        var workspace = projects.workspace(project.getEnterpriseId(), project.getUserId(), project.getWorkspaceId());
        var location = new ProjectLocation(workspace.getId(), project.getId(), project.getEnterpriseId(),
            project.getUserId(), project.getDirectoryPath());
        long deadline = System.nanoTime() + timeout.toNanos();
        UserWorkspaceLock lock = null;
        try {
            // 新项目可以重新建立用户基础目录；旧项目仍必须保留原目录，不能把空目录当成文件已恢复。
            layout.prepareWorkspace(workspace.getId(), project.getInitializedAt() != null);
            lock = UserWorkspaceLock.acquire(layout, workspace.getId(), timeout, control);
            Path pending = layout.control(workspace.getId()).resolve("container.pending");
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                lock.close();
                lock = null;
                executor.recover(location, remaining(deadline), control);
                lock = UserWorkspaceLock.acquire(layout, workspace.getId(), remaining(deadline), control);
            }
            Path directory = layout.project(location);
            WorkspacePaths.requireInside(layout.root(), directory);
            if (project.getInitializedAt() != null) {
                layout.requireRegistered(location);
                if (!Files.isRegularFile(layout.state(location), LinkOption.NOFOLLOW_LINKS)) {
                    throw ProjectWorkspaceLayout.unavailable();
                }
            } else {
                Files.createDirectories(directory);
                for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
                    Files.createDirectories(directory.resolve(area));
                }
                if (!Files.exists(layout.state(location), LinkOption.NOFOLLOW_LINKS)) {
                    var origins = migration.copy(location, project.getLegacyConversationId(), directory, control,
                        remaining(deadline));
                    layout.write(layout.state(location),
                        new WorkspaceStore.Manifest(UUID.randomUUID().toString(), location.enterpriseId(),
                            location.userId(),
                            location.projectId(), location.projectId(), null, 0, origins.results(), origins.inputs(),
                            System.currentTimeMillis()));
                }
                layout.register(location);
                projects.initialized(workspace, project);
            }
            inputFiles.retain(location, load(location).inputFileIds());
            requireLease.run();
            return new Scope(location, run, session, control, requireLease, deadline, lock);
        } catch (IOException | RuntimeException failure) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (IOException closing) {
                    failure.addSuppressed(closing);
                }
            }
            if (failure instanceof IOException io) {
                throw WorkspacePaths.io(io);
            }
            throw (RuntimeException) failure;
        }
    }

    private WorkspaceStore.Manifest load(ProjectLocation location) {
        try {
            var saved = layout.read(layout.state(location), WorkspaceStore.Manifest.class);
            if (!location.workspaceId().equals(ProjectPaths.workspaceId(saved.enterpriseId(), saved.userId()))
                || !location.projectId().equals(saved.conversationId()) || saved.version() == null
                || saved.sourceResultIds() == null || saved.inputFileIds() == null) {
                throw ProjectWorkspaceLayout.unavailable();
            }
            return saved;
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                "WORKSPACE_TIMEOUT", "本次文件操作超过允许时间，请稍后重试。");
        }
        return Duration.ofNanos(nanos);
    }

    private final class Scope implements WorkspaceSession {
        private final ProjectLocation location;
        private final RunRecord run;
        private final String session;
        private final ToolCallControl control;
        private final Runnable requireLease;
        private final long deadline;
        private UserWorkspaceLock lock;

        private Scope(ProjectLocation location, RunRecord run, String session, ToolCallControl control,
                      Runnable requireLease,
                      long deadline, UserWorkspaceLock lock) {
            this.location = location;
            this.run = run;
            this.session = session;
            this.control = control;
            this.requireLease = requireLease;
            this.deadline = deadline;
            this.lock = lock;
        }

        @Override
        public WorkspaceStore.Manifest manifest() {
            var saved = load(location);
            try {
                String version = Utf8Text.revision(saved.version(), layout.fileRevision(location.workspaceId()));
                return new WorkspaceStore.Manifest(version, saved.enterpriseId(), saved.userId(),
                    run == null ? session : run.conversationId(),
                    session, saved.runId(), saved.leaseVersion(), saved.sourceResultIds(), saved.inputFileIds(),
                    saved.savedAt());
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
        }

        @Override
        public Path directory() {
            return layout.control(location.workspaceId());
        }

        @Override
        public boolean cleared() {
            return false;
        }

        @Override
        public boolean persistent() {
            return true;
        }

        @Override
        public Duration remaining() {
            control.requireActive();
            return ProjectWorkspaceStore.remaining(deadline);
        }

        @Override
        public WorkspaceFilesystem current() {
            return new WorkspaceFilesystem(layout.project(location), settings, this::remaining, false, true);
        }

        @Override
        public WorkspaceFilesystem prepare() {
            return new WorkspaceFilesystem(layout.project(location), settings, this::remaining, true, true);
        }

        @Override
        public void commit(Set<String> sources, Set<String> inputs) {
            remaining();
            requireLease.run();
            var previous = load(location);
            var resultIds = new LinkedHashSet<>(previous.sourceResultIds());
            resultIds.addAll(sources);
            var inputIds = new LinkedHashSet<>(previous.inputFileIds());
            inputIds.addAll(inputs);
            inputFiles.retain(location, inputIds);
            try {
                layout.write(layout.state(location),
                    new WorkspaceStore.Manifest(UUID.randomUUID().toString(), location.enterpriseId(),
                        location.userId(),
                        location.projectId(), location.projectId(), run == null ? previous.runId() : run.id(),
                        run == null ? 0 : run.leaseVersion(),
                        Set.copyOf(resultIds), Set.copyOf(inputIds), System.currentTimeMillis()));
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
        }

        @Override
        public SandboxExecutor.Result execute(String callId, String command, String workingDirectory, Duration timeout,
                                              ToolCallControl operation) {
            try {
                lock.close();
                lock = null;
                if (run != null && sandboxUsage != null) {
                    sandboxUsage.touch(run, session, location, ProjectWorkspaceStore.remaining(deadline), control);
                }
                var result = executor.execute(location, callId, command, workingDirectory, timeout, operation);
                lock = UserWorkspaceLock.acquire(layout, location.workspaceId(),
                    ProjectWorkspaceStore.remaining(deadline), control);
                return result;
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
        }

        @Override
        public void close() throws IOException {
            if (lock != null) {
                lock.close();
            }
        }
    }
}
