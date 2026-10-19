package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.environment.response.EnvironmentView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.project.ProjectPaths;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserSandboxRegistry;
import com.stonewu.agenteam.service.project.UserWorkspaceLock;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.WorkspaceFilesystem;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Clock;
import java.time.Duration;

/** 从登录身份派生环境位置，接口不接收用户编号或主机目录。 */
@Service
public class EnvironmentWorkspaceService {
    private final EnterpriseAuthorizationService authorization;
    private final ProjectMetadataService metadata;
    private final ProjectWorkspaceLayout layout;
    private final UserSandboxRegistry registry;
    private final UserEnvironmentRuntime runtime;
    private final WorkspaceSettings settings;
    private final SandboxLifecycleSettings lifecycle;
    private final Clock clock;

    public EnvironmentWorkspaceService(EnterpriseAuthorizationService authorization, ProjectMetadataService metadata,
            ProjectWorkspaceLayout layout, UserSandboxRegistry registry, UserEnvironmentRuntime runtime,
            WorkspaceSettings settings, SandboxLifecycleSettings lifecycle, Clock clock) {
        this.authorization = authorization;
        this.metadata = metadata;
        this.layout = layout;
        this.registry = registry;
        this.runtime = runtime;
        this.settings = settings;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    public ProjectLocation location(AuthContext actor) {
        authorization.require(actor, "agent.run");
        return new ProjectLocation(ProjectPaths.workspaceId(actor.enterpriseId(), actor.userId()), "user-environment",
            actor.enterpriseId(), actor.userId(), "environment");
    }

    public EnvironmentView status(AuthContext actor) {
        var project = location(actor);
        if (!settings.executeEnabled()) {
            return new EnvironmentView(settings.executeEnabled(), "stopped", false, lifecycle.idleSeconds(), null);
        }
        if (!Files.exists(layout.registration(project), LinkOption.NOFOLLOW_LINKS)) {
            try {
                var state = registry.state(project.workspaceId());
                if (state == null) {
                    return new EnvironmentView(true, "stopped", false, lifecycle.idleSeconds(), null);
                }
                project = state.project();
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
        }
        return runtime.manage("status", project);
    }

    public EnvironmentView connect(AuthContext actor) {
        return runtime.manage("connect", prepare(actor));
    }

    public EnvironmentView stop(AuthContext actor) {
        var project = prepare(actor);
        return runtime.manage("stop", project);
    }

    public ProjectLocation prepare(AuthContext actor) {
        var project = location(actor);
        if (!settings.executeEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_DISABLED", "当前工作空间暂未开放环境连接，请联系管理员。");
        }
        var workspace = metadata.userWorkspace(actor);
        try {
            layout.prepareWorkspace(workspace.getId(), workspace.getInitializedAt() != null);
            try (var control = new ToolCallControl(() -> {
            }); var lock = UserWorkspaceLock.acquire(layout, workspace.getId(), Duration.ofSeconds(15), control)) {
                var directory = layout.project(project);
                WorkspacePaths.requireInside(layout.root(), directory);
                WorkspacePaths.requireInside(layout.root(), directory.resolve("work"));
                Files.createDirectories(directory.resolve("work"));
                layout.register(project);
                metadata.initializedWorkspace(workspace);
            }
            return project;
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    public void activity(AuthContext actor) {
        var project = location(actor);
        if (!Files.isDirectory(layout.control(project.workspaceId()), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, project.workspaceId(), Duration.ofSeconds(10), control)) {
            var state = registry.state(project.workspaceId());
            if (state != null && "running".equals(state.status())) {
                registry.markActivity(project.workspaceId(), clock.millis());
            }
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    public WorkspaceFilesystem files(AuthContext actor) {
        var project = location(actor);
        return new WorkspaceFilesystem(layout.files(project.workspaceId()), settings, () -> {
        }, false, true);
    }
}
