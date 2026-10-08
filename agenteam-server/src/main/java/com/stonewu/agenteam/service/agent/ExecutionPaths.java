package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 工作文件与框架状态分别存放，路径包括企业、用户、执行和领取版本。
 */
@Component
public class ExecutionPaths {
    private final Path workspaceRoot;
    private final Path stateRoot;

    public ExecutionPaths(@Value("${execution.workspace-root:.agenteam/workspaces}") String workspace,
                          @Value("${execution.state-root:.agenteam/states}") String state) {
        workspaceRoot = Path.of(workspace).toAbsolutePath().normalize();
        stateRoot = Path.of(state).toAbsolutePath().normalize();
    }

    public Path workspace(RunRecord run) {
        return path(workspaceRoot, run);
    }

    public Path state(RunRecord run) {
        return path(stateRoot, run);
    }

    public Path checkpoint(RunRecord run, String id) {
        return state(run).resolve("checkpoints").resolve(id);
    }

    public String checkpointKey(RunRecord run, String id) {
        return stateRoot.relativize(checkpoint(run, id)).toString().replace('\\', '/');
    }

    public Path checkpointPath(RunRecord run, long lease, String key) {
        Path resolved = stateRoot.resolve(key).toAbsolutePath().normalize();
        Path expected = state(run).getParent().resolve("lease-" + lease).resolve("checkpoints");
        if (!resolved.startsWith(expected) || !resolved.getParent().equals(expected)) {
            throw new IllegalStateException("检查点不属于本次执行");
        }
        return resolved;
    }

    private Path path(Path root, RunRecord run) {
        return root.resolve("enterprise").resolve(EncryptedAgentStateStore.component(run.enterpriseId()))
            .resolve("user").resolve(EncryptedAgentStateStore.component(run.userId()))
            .resolve("run").resolve(EncryptedAgentStateStore.component(run.id()))
            .resolve("lease-" + run.leaseVersion());
    }
}
