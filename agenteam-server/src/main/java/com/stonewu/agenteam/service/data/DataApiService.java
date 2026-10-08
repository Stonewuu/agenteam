package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.file.FileDataMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.data.request.DataCollectionWriteRequest;
import com.stonewu.agenteam.model.data.request.DataQueryRequest;
import com.stonewu.agenteam.model.data.response.DataCollectionView;
import com.stonewu.agenteam.model.data.response.DataQueryResultView;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 集合接口只传递已验证的数据，数字比较值使用请求过滤器保留的精确原值。
 */
@Service
public class DataApiService {
    private final AuthContextService identity;
    private final DataResourcePolicy policy;
    private final DataCollectionMapper collections;
    private final DataFieldValidation fields;
    private final FileMapper files;
    private final FileDataMapper data;
    private final DataImportPreparation preparation;
    private final DataCollectionTransactions transactions;
    private final DataQueryLogService queries;
    private final IdempotentRequestService requests;
    private final ListPagination pagination;
    private final DataConnectionService connections;
    private final DataRemoteCollectionService remote;

    public DataApiService(AuthContextService identity, DataResourcePolicy policy, DataCollectionMapper collections,
                          DataFieldValidation fields,
                          FileMapper files, FileDataMapper data, DataImportPreparation preparation,
                          DataCollectionTransactions transactions,
                          DataQueryLogService queries, IdempotentRequestService requests, ListPagination pagination,
                          DataConnectionService connections, DataRemoteCollectionService remote) {
        this.identity = identity;
        this.policy = policy;
        this.collections = collections;
        this.fields = fields;
        this.files = files;
        this.data = data;
        this.preparation = preparation;
        this.transactions = transactions;
        this.queries = queries;
        this.requests = requests;
        this.pagination = pagination;
        this.connections = connections;
        this.remote = remote;
    }

    public PageResponse<DataCollectionView> list(String enterprise, String resource, Integer size, String cursor,
                                                 HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        policy.view(actor, resource);
        int limit = pagination.limit(size);
        var binding = new ListPagination.Binding(actor.userId(), enterprise, "data:" + resource, "", "updated_desc");
        var rows = collections.list(enterprise, resource, pagination.read(cursor, binding), limit).stream()
            .map(
                row -> DataCollectionMapper.view(row, collections.fields(enterprise, row.id(), row.activeGeneration())))
            .toList();
        return pagination.page(rows, limit, binding, row -> new PagePosition(Instant.parse(row.updatedAt()), row.id()));
    }

    public ApiOperationResult update(String enterprise, String resource, String id, DataCollectionWriteRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, DataCollectionWriteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        Runnable authorize = () -> policy.edit(identity.requireEnterprise(request.getSession(false), enterprise),
            resource, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var definition = policy.edit(actor, resource, false);
        var collection = policy.collection(enterprise, resource, id, false);
        policy.revision(collection, revision);
        var checkedFields = fields.fields(input.fields());
        if (!definition.config().path("sourceType").asText().equals("file")) {
            remote.inspect(definition, input, checkedFields);
            return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
                () -> ApiOperationResult.of(200, remote.save(actor, definition, id, revision, input, checkedFields)));
        }
        var source = files.find(enterprise, collection.fileId(), false).filter(
                file -> resource.equals(file.resourceId()) && file.status().equals("ready") && file.deletedAt() == null)
            .orElseThrow(FileAccessService::unavailable);
        try (var prepared = preparation.prepare(source,
            data.profile(source).orElseThrow(PreparedDataImport::unavailable), checkedFields)) {
            return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
                () -> ApiOperationResult.of(200,
                    transactions.updateFile(actor, resource, id, revision, input, prepared)));
        }
    }

    public ApiOperationResult create(String enterprise, String resource, DataCollectionWriteRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, DataCollectionWriteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> policy.edit(identity.requireEnterprise(request.getSession(false), enterprise),
            resource, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var definition = policy.edit(actor, resource, false);
        var checkedFields = fields.fields(input.fields());
        remote.inspect(definition, input, checkedFields);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(201, remote.save(actor, definition, null, null, input, checkedFields)));
    }

    public ApiOperationResult check(String enterprise, String resource, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        Runnable authorize = () -> policy.edit(identity.requireEnterprise(request.getSession(false), enterprise),
            resource, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var definition = connections.prepare(actor, resource, revision);
        var checked = connections.inspect(definition);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(200, connections.save(actor, definition, checked)));
    }

    public DataQueryResultView query(String enterprise, String resource, HttpServletRequest request) {
        InputValidation.request(request, DataQueryRequest.class);
        return queries.query(identity.requireEnterprise(request.getSession(false), enterprise), resource,
            queryInput((JsonNode) request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE)));
    }

    public static DataQueryRequest queryInput(JsonNode body) {
        var fields = new ArrayList<String>();
        body.path("fields").forEach(field -> fields.add(field.asText()));
        var filters = new ArrayList<DataQueryRequest.Filter>();
        body.path("filters").forEach(filter -> filters.add(
            new DataQueryRequest.Filter(filter.path("field").asText(), filter.path("operator").asText(),
                filter.get("value"))));
        var order = new ArrayList<DataQueryRequest.Sort>();
        body.path("sort").forEach(
            sort -> order.add(new DataQueryRequest.Sort(sort.path("field").asText(), sort.path("direction").asText())));
        return new DataQueryRequest(body.path("collectionId").asText(), body.path("generation").asInt(),
            List.copyOf(fields), List.copyOf(filters), List.copyOf(order),
            body.has("limit") ? body.path("limit").asInt() : null,
            body.has("offset") ? body.path("offset").asInt() : null);
    }
}
