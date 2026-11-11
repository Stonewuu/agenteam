package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.agent.BuiltinAgentTemplate;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工作流助手仅获得管理插件，所有操作仍以对话用户的当前权限执行。 */
@Component
public class WorkflowBuilderTemplate implements BuiltinAgentTemplate {
    private final ResourcePolicy resources;
    private final EnterpriseAuthorizationService authorization;

    public WorkflowBuilderTemplate(ResourcePolicy resources, EnterpriseAuthorizationService authorization) {
        this.resources = resources;
        this.authorization = authorization;
    }

    @Override
    public String code() {
        return "workflow_builder";
    }

    @Override
    public String name() {
        return Messages.source("text.workflow.workflowBuilderTemplate.name.1");
    }

    @Override
    public String description() {
        return Messages.source("text.workflow.workflowBuilderTemplate.description.1");
    }

    @Override
    public List<String> pluginCodes() {
        return List.of("workflow_management");
    }

    @Override
    public String instructions() {
        return Messages.source("text.workflow.workflowBuilderTemplate.instructions.1");
    }

    @Override
    public String prepareInput(AuthContext actor, String targetResourceId, String message) {
        if (targetResourceId == null) {
            authorization.require(actor, "workflow.create");
            return message;
        }
        var resource = resources.authorize(actor, targetResourceId, "edit", false, false);
        if (resource.kind() != ResourceKind.WORKFLOW) {
            throw ResourceAuthorizationService.unavailable();
        }
        return Messages.source("text.workflow.workflowBuilderTemplate.prepareInput.1", resource.id(), message);
    }
}
