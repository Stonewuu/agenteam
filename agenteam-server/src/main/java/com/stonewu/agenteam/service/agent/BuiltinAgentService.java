package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.agent.request.BuiltinAgentStartInput;
import com.stonewu.agenteam.model.agent.request.HireAgentRequest;
import com.stonewu.agenteam.model.agent.response.AgentHireResult.Hired;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.model.resource.request.AgentListingRequest;
import com.stonewu.agenteam.model.resource.request.ResourceCreateRequest;
import com.stonewu.agenteam.model.resource.request.ResourcePublishRequest;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.plugin.BuiltinPluginResourceService;
import com.stonewu.agenteam.service.resource.ResourceDraftService;
import com.stonewu.agenteam.service.resource.ResourcePublishService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 模板通过普通资源服务安装为本人可用的助手，同一模板、模型及插件版本只创建一次。 */
@Service
public class BuiltinAgentService {
    private final Map<String, BuiltinAgentTemplate> templates;
    private final BuiltinPluginResourceService plugins;
    private final ResourceMapper resources;
    private final ResourceAuthorizationService access;
    private final ResourceDraftService drafts;
    private final ResourcePublishService publishing;
    private final AgentHireService hires;
    private final RunSubmissionService submissions;
    private final ModelProfileCatalog models;
    private final ResourceJson json;

    public BuiltinAgentService(List<BuiltinAgentTemplate> templates, BuiltinPluginResourceService plugins,
                                ResourceMapper resources, ResourceAuthorizationService access, ResourceDraftService drafts,
                                ResourcePublishService publishing, AgentHireService hires, RunSubmissionService submissions,
                                ModelProfileCatalog models, ResourceJson json) {
        var registered = new LinkedHashMap<String, BuiltinAgentTemplate>();
        for (var template : templates) {
            if (registered.putIfAbsent(template.code(), template) != null) {
                throw new IllegalStateException("内置助手模板重复登记：" + template.code());
            }
        }
        this.templates = Map.copyOf(registered);
        this.plugins = plugins;
        this.resources = resources;
        this.access = access;
        this.drafts = drafts;
        this.publishing = publishing;
        this.hires = hires;
        this.submissions = submissions;
        this.models = models;
        this.json = json;
    }

    public BuiltinAgentTemplate require(String code) {
        var template = templates.get(code);
        if (template == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return template;
    }

    @Transactional
    public RunAccepted start(AuthContext actor, String code, BuiltinAgentStartInput input) {
        var template = require(code);
        String message = template.prepareInput(actor, input.targetResourceId(), input.message());
        var model = models.requireAvailable(actor.enterpriseId(), input.modelProfileId());
        if (!model.capabilities().supportsTools() || !model.capabilities().inputTypes().contains("text")) {
            throw ApiException.invalidField("modelProfileId", "请选择支持文字和工具调用的模型。");
        }
        plugins.initialize(actor.enterpriseId());
        var versions = new ArrayList<String>();
        for (String plugin : template.pluginCodes()) {
            String id = stableId("builtin-plugin:" + actor.enterpriseId() + ":" + plugin);
            access.requireUse(actor, id, "plugin");
            versions.add(resources.find(actor.enterpriseId(), id, false, false).orElseThrow(ResourceAuthorizationService::unavailable).publishedVersionId());
        }
        ObjectNode config = json.tree(Map.of("icon", "GitBranch", "color", "blue", "agentType", "task", "businessRole", template.description(),
            "modelProfileId", input.modelProfileId(), "instructions", template.instructions(), "maxSteps", 30,
            "timeoutSeconds", 600, "attachmentsEnabled", false, "welcomeMessage", template.description())).deepCopy();
        for (String field : List.of("suggestedQuestions", "skillVersionIds", "knowledgeVersionIds", "dataVersionIds", "workflowVersionIds",
            "memoryFields", "businessTerms", "subagentVersionIds", "publicExamples")) {
            config.putArray(field);
        }
        config.set("pluginVersionIds", json.tree(versions));
        config.putNull("entryWorkflowVersionId").put("memoryEnabled", false).put("dynamicSubagentEnabled", false);
        String id = stableId("builtin-agent:" + actor.enterpriseId() + ":" + actor.userId() + ":" + code + ":" + json.hash(config));
        var detail = drafts.createAgentTemplate(actor, id, new ResourceCreateRequest("agent", template.name(), template.description(), List.of(), json.object(config)));
        if (detail.resource().publishedVersion() == null) {
            publishing.publish(actor, id, new ResourcePublishRequest("创建内置助手的个人副本", null,
                new AgentListingRequest(true, "automatic", List.of())), Long.parseLong(detail.resource().revision()));
        }
        if (!(hires.hire(actor, new HireAgentRequest(id, "使用内置助手")) instanceof Hired)) {
            throw ApiException.invalidField("agentId", "请先完成这位助手的使用审批。");
        }
        return submissions.create(actor, new NewConversationInput(id,
            new MessageInput(message, List.of(), List.of(), List.of(), List.of()), "default"));
    }

    private String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
