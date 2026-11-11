package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.tool.CommandResultSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.workspace.entity.WorkspaceLimitValues;
import com.stonewu.agenteam.model.workspace.request.WorkspaceLimitsRequest;
import com.stonewu.agenteam.model.workspace.response.WorkspaceLimitsView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 只有系统超级管理员可以调整所有用户共用的工作空间限制。 */
@Service
public class WorkspaceLimitsManagement {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceLimitsManagement.class);
    private final WorkspaceLimitsStore store;
    private final WorkspaceSettings workspace;
    private final CommandResultSettings results;

    public WorkspaceLimitsManagement(WorkspaceLimitsStore store, WorkspaceSettings workspace,
                                     CommandResultSettings results) {
        this.store = store;
        this.workspace = workspace;
        this.results = results;
    }

    public WorkspaceLimitsView read(UserEntity actor) {
        authorize(actor);
        return view(store.current());
    }

    public WorkspaceLimitsView save(UserEntity actor, long revision, WorkspaceLimitsRequest input) {
        authorize(actor);
        InputValidation.validate(input);
        if (input.fileMb() > input.workspaceMb()) {
            throw ApiException.invalidField("fileMb", Messages.text("error.workspace.workspaceLimitsManagement.save.1"));
        }
        var saved = store.save(revision, new WorkspaceLimitValues(input.workspaceMb(), input.fileMb(),
            input.maximumFiles(), input.commandResultsMb()));
        LOG.info(Messages.log("log.workspace.workspaceLimitsManagement.save.1"),
            actor.id(), saved.revision(), input.workspaceMb(), input.fileMb(), input.maximumFiles(), input.commandResultsMb());
        return view(saved);
    }

    private WorkspaceLimitsView view(WorkspaceLimitsStore.Saved saved) {
        var limits = saved == null ? new WorkspaceLimitValues(workspace.workspaceMb(), workspace.fileMb(),
            workspace.maximumFiles(), results.maximumMb()) : saved.limits();
        return new WorkspaceLimitsView(String.valueOf(saved == null ? 1 : saved.revision()), limits.workspaceMb(),
            limits.fileMb(), limits.maximumFiles(), limits.commandResultsMb());
    }

    private static void authorize(UserEntity actor) {
        if (actor == null || !actor.superAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_SETTINGS_DENIED", Messages.text("error.workspace.workspaceLimitsManagement.authorize.1"));
        }
    }
}
