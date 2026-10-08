package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.knowledge.request.KnowledgeQueryRequest;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.service.data.DataApiService;
import com.stonewu.agenteam.service.data.DataQueryService;
import com.stonewu.agenteam.service.data.DataResourcePolicy;
import com.stonewu.agenteam.service.data.DataSourceHash;
import com.stonewu.agenteam.service.data.mysql.DatabaseCredentialService;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.knowledge.KnowledgeSearchService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 资料工具使用实际操作者和已固定配置，研究子任务与父任务遵循同一套当前授权。
 */
@Service
public class SourceToolExecutionService {
    private final ExecutionAccessService access;
    private final KnowledgeSearchService knowledge;
    private final DataQueryService data;
    private final DataResourcePolicy dataAccess;
    private final DataCollectionMapper collections;
    private final DataSourceHash sourceHash;
    private final ResourceJson json;
    private final ListPagination pagination;
    private final DatabaseCredentialService credentials;

    public SourceToolExecutionService(ExecutionAccessService access, KnowledgeSearchService knowledge,
                                      DataQueryService data, DataResourcePolicy dataAccess,
                                      DataCollectionMapper collections, DataSourceHash sourceHash, ResourceJson json,
                                      ListPagination pagination, DatabaseCredentialService credentials) {
        this.access = access;
        this.knowledge = knowledge;
        this.data = data;
        this.dataAccess = dataAccess;
        this.collections = collections;
        this.sourceHash = sourceHash;
        this.json = json;
        this.pagination = pagination;
        this.credentials = credentials;
    }

    public JsonNode invoke(RunRecord run, ExecutionToolBinding binding, JsonNode arguments, Duration remaining,
                           ToolCallControl control) {
        var actor = access.actor(run);
        control.requireActive();
        JsonNode result;
        switch (binding.definition().name()) {
            case "knowledge_search" -> {
                control.beforeSend();
                var citations = knowledge.search(actor, binding.resourceId(),
                    new KnowledgeQueryRequest(arguments.path("query").asText(),
                        arguments.has("limit") ? arguments.path("limit").asInt() : null), binding.config());
                result = json.tree(Map.of("isError", false, "citations", citations));
            }
            case "data_collections" -> {
                control.beforeSend();
                dataAccess.use(actor, binding.resourceId());
                int limit = arguments.path("limit").asInt(1);
                var paging = new ListPagination.Binding(actor.userId(), actor.enterpriseId(),
                    "data-tools:" + binding.resourceVersionId(), "", "updated_desc");
                var position = pagination.read(arguments.path("cursor").asText(null), paging);
                var available = collections.available(actor.enterpriseId(), binding.resourceId(),
                        sourceHash.calculate(binding.config()), position, limit).stream()
                    .map(collection -> DataCollectionMapper.view(collection,
                        collections.fields(actor.enterpriseId(), collection.id(), collection.activeGeneration())
                            .stream().filter(DataField::readable).toList())).toList();
                result = json.tree(pagination.page(available, limit, paging,
                    value -> new PagePosition(Instant.parse(value.updatedAt()), value.id())));
                dataAccess.use(actor, binding.resourceId());
            }
            case "data_query" -> result = json.tree(
                data.query(actor, binding.resourceId(), DataApiService.queryInput(arguments), binding.config(), null,
                    remaining, control));
            default -> throw new IllegalStateException("资料工具名称未登记");
        }
        access.actor(run);
        control.requireActive();
        return result;
    }

    public List<String> databaseSecrets(RunRecord run, ExecutionToolBinding binding) {
        if (!binding.resourceKind().equals("data") || !binding.config().path("sourceType").asText().equals("mysql")) {
            return List.of();
        }
        String password = credentials.resolve(run.enterpriseId(), binding.config().path("credentialId").asText(null))
            .password();
        return password.isEmpty() ? List.of() : List.of(password);
    }
}
