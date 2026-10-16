package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.plugin.PlatformToolInputMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.workflow.WorkflowDraftMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowValidationMapper;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.request.DraftWriteRequest;
import com.stonewu.agenteam.model.resource.request.ResourceCreateRequest;
import com.stonewu.agenteam.model.resource.request.ResourceListQuery;
import com.stonewu.agenteam.model.resource.response.ResourceDetailView;
import com.stonewu.agenteam.model.resource.response.TagView;
import com.stonewu.agenteam.model.workflow.response.WorkflowValidationView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.plugin.PlatformToolHandler;
import com.stonewu.agenteam.service.resource.ResourceDraftService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import com.stonewu.agenteam.service.resource.ResourceQueryService;
import com.stonewu.agenteam.service.resource.UsableVersionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 智能体与手工编辑共用草稿服务，身份只来自真实执行，修改必须携带已读取的版本。 */
@Service
public class WorkflowAuthoringService implements PlatformToolHandler {
    private final ResourceDraftService drafts;
    private final ResourceQueryService queries;
    private final ResourcePolicy policy;
    private final UsableVersionService versions;
    private final WorkflowDependencyOptionsService options;
    private final WorkflowPreparationService preparation;
    private final WorkflowValidationMapper validation;
    private final WorkflowDraftMapper mapper;
    private final ResourceJson json;
    private final PlatformToolInputMapper input;
    private final EnterpriseAuthorizationService authorization;

    public WorkflowAuthoringService(ResourceDraftService drafts, ResourceQueryService queries, ResourcePolicy policy,
                                     UsableVersionService versions, WorkflowDependencyOptionsService options,
                                     WorkflowPreparationService preparation, WorkflowValidationMapper validation,
                                     WorkflowDraftMapper mapper, ResourceJson json, PlatformToolInputMapper input,
                                     EnterpriseAuthorizationService authorization) {
        this.drafts = drafts;
        this.queries = queries;
        this.policy = policy;
        this.versions = versions;
        this.options = options;
        this.preparation = preparation;
        this.validation = validation;
        this.mapper = mapper;
        this.json = json;
        this.input = input;
        this.authorization = authorization;
    }

    @Override
    public Set<String> toolNames() {
        return Set.of("workflow_schema", "workflow_list", "workflow_get", "workflow_capabilities", "workflow_dependency",
            "workflow_create", "workflow_save", "workflow_patch", "workflow_validate");
    }

    @Override
    public JsonNode call(BuiltinExecutionContext context, String name, JsonNode args) {
        var actor = context.actor();
        authorization.require(actor, "capabilities.view");
        String id = input.text(args, "workflowId");
        Object result = switch (name) {
            case "workflow_schema" -> mapper.schema();
            case "workflow_list" -> queries.list(actor, new ResourceListQuery("workflow", input.text(args, "query"),
                null, null, null, List.of(), "updated_desc", input.text(args, "cursor"), input.limit(args)));
            case "workflow_capabilities" -> {
                String kind = input.text(args, "kind");
                if (!Set.of("agent", "plugin").contains(kind == null ? "" : kind)) {
                    throw ApiException.invalidField("kind", "请选择智能体或插件。");
                }
                yield versions.list(actor, kind, input.text(args, "query"), input.text(args, "cursor"), 20, null, false, null, true);
            }
            case "workflow_dependency" -> options.options(actor, input.text(args, "versionId"));
            case "workflow_create" -> {
                var request = new ResourceCreateRequest("workflow", input.text(args, "name"), input.text(args, "description", ""),
                    List.of(), json.object(args.has("config") ? args.path("config") : mapper.initial()));
                InputValidation.validate(request);
                yield saved(actor.enterpriseId(), drafts.create(actor, request));
            }
            case "workflow_get", "workflow_save", "workflow_patch", "workflow_validate" -> {
                var resource = policy.authorize(actor, id, name.equals("workflow_get") ? "view" : "edit", false, false);
                if (resource.kind() != ResourceKind.WORKFLOW) {
                    throw ResourceAuthorizationService.unavailable();
                }
                var detail = queries.detail(actor, id);
                if (name.equals("workflow_get")) {
                    yield Map.of("workflowId", id, "revision", detail.resource().revision(), "name", detail.resource().name(),
                        "description", detail.resource().description(), "config", detail.draft());
                }
                JsonNode config = name.equals("workflow_patch") ? mapper.patch(resource.config(), args.path("operations"))
                    : args.has("config") ? args.path("config") : resource.config();
                if (name.equals("workflow_validate")) {
                    try {
                        preparation.prepare(actor, resource, config);
                        yield new WorkflowValidationView(true, List.of());
                    } catch (ApiException error) {
                        if (!Set.of(409, 422).contains(error.getStatusCode().value())) {
                            throw error;
                        }
                        yield validation.invalid(error, config);
                    }
                }
                var request = new DraftWriteRequest(input.text(args, "name", resource.name()),
                    input.text(args, "description", resource.description()), detail.resource().tags().stream().map(TagView::id).toList(), json.object(config));
                InputValidation.validate(request);
                yield saved(actor.enterpriseId(), drafts.save(actor, id, input.revision(args), request));
            }
            default -> throw new IllegalArgumentException("工作流工具未登记");
        };
        return json.tree(result);
    }

    private Object saved(String enterprise, ResourceDetailView detail) {
        var resource = detail.resource();
        return Map.of("workflowId", resource.id(), "revision", resource.revision(), "name", resource.name(),
            "content", "工作流草稿已保存。", "url", "/enterprises/" + enterprise + "/capabilities/workflows/" + resource.id());
    }
}
