package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.SourceToolResultAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 工作进程从执行记录恢复实际身份，不继承浏览器会话或机器默认企业。
 */
@Service
public class ExecutionAccessService {
    private final AuthMapper users;
    private final PermissionMapper permissions;
    private final ExecutionConfigurationService configurations;
    private final ExecutionSourceService sources;
    private final SourceToolResultAccessService sourceResults;

    public ExecutionAccessService(AuthMapper users, PermissionMapper permissions,
                                  ExecutionConfigurationService configurations, ExecutionSourceService sources,
                                  SourceToolResultAccessService sourceResults) {
        this.users = users;
        this.permissions = permissions;
        this.configurations = configurations;
        this.sources = sources;
        this.sourceResults = sourceResults;
    }

    public AuthContext actor(RunRecord run) {
        var user = users.findById(run.userId()).filter(value -> value.status().equals("active"))
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号已不能继续执行任务。"));
        var actor = new AuthContext(user, run.enterpriseId(),
            Set.copyOf(permissions.listPermissionCodes(user.id(), run.enterpriseId())));
        if (run.mode().equals("preview")) {
            if (run.executionConfig().has("workflowId")) {
                configurations.workflowPreview(actor, run.executionConfig().path("workflowId").asText(),
                    run.executionConfig().path("config"));
            } else {
                configurations.preview(actor, run.executionConfig().path("agentId").asText(),
                    run.executionConfig().path("config"));
            }
        } else {
            configurations.normal(actor, run.executionConfig().path("agentId").asText(), run.agentVersionId(),
                ModelSelection.fromConfig(run.executionConfig().path("config")));
        }
        sources.requireCurrent(actor, run);
        sourceResults.requireCurrent(actor, run);
        return actor;
    }

    public static String permission(RunRecord run) {
        if (!run.mode().equals("preview")) {
            return "agent.run";
        }
        return run.executionConfig().has("workflowId") ? "workflow.preview" : "agent.preview";
    }
}
