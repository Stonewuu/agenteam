package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionConfigurationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.PreviewInput;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.modelprofile.AgentModelSelectionService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ExecutionDependencyService;
import com.stonewu.agenteam.service.resource.ResourceConfigurationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import com.stonewu.agenteam.service.workflow.WorkflowPreparationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能体能力来自固定版本，模型和思考等级采用本次选择；提交和实际开始都检查使用资格。
 */
@Service
public class ExecutionConfigurationService {
    public record Selection(String agentId, String versionId, String hireId, String name, ObjectNode snapshot) {
    }

    public record InputOptions(String versionId, JsonNode config, JsonNode dependencies) {
    }

    private record PublishedAgent(ResourceRecord resource, ResourceVersionRecord version, String hireId) {
    }

    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final AgentHireMapper hires;
    private final ResourceAuthorizationService access;
    private final ResourceConfigurationService configurations;
    private final ExecutionDependencyService dependencies;
    private final ResourcePolicy policy;
    private final ObjectMapper json;
    private final WorkflowPreparationService workflows;
    private final ExecutionConfigurationMapper snapshots;
    private final AgentModelSelectionService modelSelections;

    public ExecutionConfigurationService(ResourceMapper resources, ResourceVersionMapper versions,
                                         AgentHireMapper hires,
                                         ResourceAuthorizationService access,
                                         ResourceConfigurationService configurations,
                                         ExecutionDependencyService dependencies, ResourcePolicy policy,
                                         ObjectMapper json,
                                         WorkflowPreparationService workflows, ExecutionConfigurationMapper snapshots,
                                         AgentModelSelectionService modelSelections) {
        this.resources = resources;
        this.versions = versions;
        this.hires = hires;
        this.access = access;
        this.configurations = configurations;
        this.dependencies = dependencies;
        this.policy = policy;
        this.json = json;
        this.workflows = workflows;
        this.snapshots = snapshots;
        this.modelSelections = modelSelections;
    }

    public Selection normal(AuthContext actor, String agent, String fixedVersion) {
        return normal(actor, agent, fixedVersion, null);
    }

    public Selection normal(AuthContext actor, String agent, String fixedVersion, ModelSelection modelSelection) {
        var source = published(actor, agent, fixedVersion);
        var version = source.version();
        var config = modelSelections.apply(actor, version.config(), modelSelection);
        var snapshot = snapshot(actor, source.resource(), config, version.name());
        snapshot.put("agentVersionId", version.id());
        return new Selection(agent, version.id(), source.hireId(), version.name(), snapshot);
    }

    /**
     * 读取已授权的固定版本，模型是否可选由具体操作检查，允许更换已停用的默认模型。
     */
    private PublishedAgent published(AuthContext actor, String agent, String fixedVersion) {
        access.requireUse(actor, agent, "agent");
        var resource = resources.find(actor.enterpriseId(), agent, false, false)
            .filter(row -> row.kind() == ResourceKind.AGENT).orElseThrow(ResourceAuthorizationService::unavailable);
        String id = fixedVersion == null ? resource.publishedVersionId() : fixedVersion;
        var version = id == null ? null : versions.find(actor.enterpriseId(), id)
            .filter(row -> row.resourceId().equals(agent) && row.status().equals("available")).orElse(null);
        if (version == null) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_UNAVAILABLE",
                "此对话使用的员工版本已不可用，请使用可用版本新建对话。");
        }
        var hire = hires.forAgent(actor.enterpriseId(), actor.userId(), agent, false)
            .filter(row -> row.status().equals("active"))
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AGENT_UNAVAILABLE",
                "请先雇佣该员工，或恢复已暂停的雇佣关系。"));
        return new PublishedAgent(resource, version, hire.id());
    }

    public InputOptions inputOptions(AuthContext actor, String agent, String fixedVersion) {
        var source = published(actor, agent, fixedVersion);
        var version = source.version();
        var snapshot = snapshot(actor, source.resource(), version.config(), version.name(), true);
        return new InputOptions(version.id(), snapshot.path("config"), snapshot.path("dependencies"));
    }

    public Selection preview(AuthContext actor, String agent, JsonNode config) {
        var resource = policy.authorize(actor, agent, "preview", true, false);
        if (resource.kind() != ResourceKind.AGENT || !resource.status().equals("active")) {
            throw ResourceAuthorizationService.unavailable();
        }
        var snapshot = snapshot(actor, resource, config, resource.name());
        snapshot.putNull("agentVersionId");
        return new Selection(agent, null, null, resource.name(), snapshot);
    }

    public Selection previewInput(AuthContext actor, String agent, PreviewInput input) {
        var resource = policy.authorize(actor, agent, "preview", true, false);
        if (resource.kind() != ResourceKind.AGENT || !resource.status().equals("active")) {
            throw ResourceAuthorizationService.unavailable();
        }
        String name = input.draft() == null ? resource.name() : ResourceInput.text(input.draft().name(), "name", 80,
            true);
        if (input.draft() != null) {
            ResourceInput.text(input.draft().description(), "description", 500, false);
            ResourceInput.tags(input.draft().tagIds());
        }
        var config = (ObjectNode) configurations.draft(ResourceKind.AGENT,
            input.draft() == null ? resource.config() : input.draft().config()).deepCopy();
        ModelSelection modelSelection = input.input().modelSelection();
        if (modelSelection == null && input.modelProfileId() != null) {
            String effort = input.modelProfileId().equals(config.path("modelProfileId").asText())
                ? config.path("reasoningEffort").asText(null) : null;
            modelSelection = new ModelSelection(input.modelProfileId(), effort);
        }
        config = modelSelections.apply(actor, config, modelSelection);
        var snapshot = snapshot(actor, resource, config, name);
        snapshot.putNull("agentVersionId");
        return new Selection(agent, null, null, name, snapshot);
    }

    public Selection workflowPreview(AuthContext actor, String workflow, JsonNode config) {
        var resource = policy.authorize(actor, workflow, "preview", true, false);
        if (resource.kind() != ResourceKind.WORKFLOW || !resource.status().equals("active")) {
            throw ResourceAuthorizationService.unavailable();
        }
        var prepared = workflows.prepare(actor, resource, config);
        var snapshot = snapshots.freeze(ResourceKind.WORKFLOW, resource.id(), resource.name(),
            prepared.resource().config(), prepared.dependencies());
        snapshot.putNull("agentVersionId");
        snapshot.putObject("limits").put("maxSteps", 100).put("timeoutSeconds", 1800);
        return new Selection(null, null, null, resource.name(), snapshot);
    }

    public MessageInput input(MessageInput value) {
        InputValidation.validate(value);
        ResourceInput.text(value.text(), "text", 20000, false);
        if (value.text().isBlank() && value.attachmentIds().isEmpty()) {
            throw ApiException.invalidField("text", "请输入任务内容。");
        }
        for (String link : value.links()) {
            URI uri = URI.create(link);
            if (!Set.of("https", "http")
                .contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw ApiException.invalidField("links", "参考链接需要使用普通或加密网页地址，且不能包含账号信息。");
            }
        }
        return new MessageInput(value.text(), value.attachmentIds(),
            List.copyOf(new LinkedHashSet<>(value.skillVersionIds())), value.knowledgeReferences(), value.links(),
            value.modelSelection());
    }

    private ObjectNode snapshot(AuthContext actor, ResourceRecord resource, JsonNode config, String name) {
        return snapshot(actor, resource, config, name, false);
    }

    private ObjectNode snapshot(AuthContext actor, ResourceRecord resource, JsonNode config, String name,
                                boolean inputOptions) {
        var configured = new ResourceRecord(resource.id(), resource.enterpriseId(), resource.kind(), name,
            resource.description(),
            config.path("agentType").asText(), resource.ownerUserId(), resource.ownerDisplayName(), resource.source(),
            resource.status(),
            resource.publishedVersionId(), resource.nextVersionNo(), resource.revision(), resource.createdAt(),
            resource.updatedAt(),
            resource.deletedAt(), config, resource.configHash(), null);
        var prepared = inputOptions ? dependencies.prepareInputOptions(actor, configured) : dependencies.prepare(actor,
            configured);
        return snapshots.freeze(ResourceKind.AGENT, resource.id(), name, prepared.resource().config(),
            prepared.dependencies());
    }
}
