package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.environment.response.EnvironmentView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.UserSandboxLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 用户操作与智能体使用同一个容器状态锁。 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "docker", matchIfMissing = true)
public class DockerUserEnvironmentRuntime implements UserEnvironmentRuntime {
    private final UserSandboxLifecycle lifecycle;

    public DockerUserEnvironmentRuntime(UserSandboxLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public EnvironmentView manage(String action, ProjectLocation project) {
        return switch (action) {
            case "status" -> lifecycle.environment(project, false);
            case "connect" -> lifecycle.environment(project, true);
            case "stop" -> lifecycle.stopEnvironment(project);
            default -> throw ApiException.invalidField("action", "不支持此环境操作。");
        };
    }
}
