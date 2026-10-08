package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.data.FileDataQueryMapper;
import com.stonewu.agenteam.mapper.data.HttpDataQueryMapper;
import com.stonewu.agenteam.mapper.data.MysqlDataQueryMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.entity.DataQueryPlan;
import com.stonewu.agenteam.model.data.request.DataQueryRequest;
import com.stonewu.agenteam.model.data.response.DataQueryResultView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据查询在返回前再次检查当前字段许可，敏感字段不交给页面或模型明文读取。
 */
@Service
public class DataQueryService {
    private final DataResourcePolicy policy;
    private final DataCollectionMapper collections;
    private final DataQueryValidation validation;
    private final FileDataQueryMapper fileQueries;
    private final FileMapper files;
    private final ObjectMapper json;
    private final AuditEventService audit;
    private final MysqlDataQueryMapper mysql;
    private final DataSourceHash sourceHash;
    private final HttpDataQueryMapper http;

    public DataQueryService(DataResourcePolicy policy, DataCollectionMapper collections, DataQueryValidation validation,
                            FileDataQueryMapper fileQueries,
                            FileMapper files, ObjectMapper json, AuditEventService audit, MysqlDataQueryMapper mysql,
                            DataSourceHash sourceHash, HttpDataQueryMapper http) {
        this.policy = policy;
        this.collections = collections;
        this.validation = validation;
        this.fileQueries = fileQueries;
        this.files = files;
        this.json = json;
        this.audit = audit;
        this.mysql = mysql;
        this.sourceHash = sourceHash;
        this.http = http;
    }

    public DataQueryResultView query(AuthContext actor, String resource, DataQueryRequest input, JsonNode fixedConfig) {
        return query(actor, resource, input, fixedConfig, null);
    }

    public DataQueryResultView query(AuthContext actor, String resource, DataQueryRequest input, JsonNode fixedConfig,
                                     Long draftRevision) {
        try (var control = new ToolCallControl(() -> {
        })) {
            return query(actor, resource, input, fixedConfig, draftRevision, Duration.ofSeconds(10), control);
        }
    }

    public DataQueryResultView query(AuthContext actor, String resource, DataQueryRequest input, JsonNode fixedConfig,
                                     Long draftRevision, Duration remaining, ToolCallControl control) {
        long start = System.nanoTime();
        var allowed = policy.use(actor, resource);
        JsonNode config = fixedConfig == null ? allowed.config() : fixedConfig;
        if (draftRevision != null && allowed.revision() != draftRevision) {
            throw ApiException.versionConflict(allowed.revision());
        }
        long deadline = start + Math.min(remaining.toNanos(),
            Math.max(1, Math.min(10, config.path("timeoutSeconds").asInt(10))) * 1_000_000_000L);
        control.requireActive();
        var plan = plan(actor, resource, input, true);
        if (!sourceHash.calculate(config)
            .equals(collections.sourceHash(actor.enterpriseId(), input.collectionId(), input.generation()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_SOURCE_CHANGED",
                "此集合版本使用的连接配置不同，请重新检查并保存集合。");
        }
        if (config.path("sourceType").asText().equals("file")) {
            String file = collections.fileForGeneration(actor.enterpriseId(), plan.collection().id(), plan.generation())
                .orElseThrow(FileAccessService::unavailable);
            files.find(actor.enterpriseId(), file, false).filter(
                    row -> row.status().equals("ready") && row.deletedAt() == null && resource.equals(row.resourceId()))
                .orElseThrow(FileAccessService::unavailable);
        }
        try {
            int byteBudget = 1024 * 1024 - bytes(
                new DataQueryResultView(input.collectionId(), input.generation(), plan.fields(), List.of(), false,
                    false, 10000)) - 1024;
            var read = switch (config.path("sourceType").asText()) {
                case "file" -> fileQueries.query(plan, deadline, byteBudget, control);
                case "mysql" -> mysql.query(actor.enterpriseId(), config, plan, deadline, byteBudget, control);
                case "http" -> http.query(actor.enterpriseId(), config, plan, deadline, byteBudget, control);
                default -> throw new ApiException(HttpStatus.CONFLICT, "DATA_SOURCE_UNAVAILABLE",
                    "此数据连接暂时不能执行查询。");
            };
            control.requireActive();
            var currentSource = policy.use(actor, resource);
            if (fixedConfig == null && !sourceHash.calculate(config)
                .equals(sourceHash.calculate(currentSource.config()))) {
                throw new ApiException(HttpStatus.CONFLICT, "DATA_SOURCE_CHANGED",
                    "数据连接在查询期间发生变化，请重新读取集合后再试。");
            }
            var latest = plan(actor, resource, input, false);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var row : read.rows()) {
                var visible = row.deepCopy();
                for (var field : latest.fields()) {
                    if (field.sensitive() && !visible.path(field.name()).isNull()) {
                        visible.put(field.name(), "已隐藏");
                    }
                }
                rows.add(json.convertValue(visible, new TypeReference<Map<String, Object>>() {
                }));
            }
            long duration = (System.nanoTime() - start) / 1_000_000;
            if (System.nanoTime() >= deadline) {
                throw timeout();
            }
            boolean truncated = read.truncated();
            var result = new DataQueryResultView(input.collectionId(), input.generation(), latest.fields(),
                List.copyOf(rows), read.hasMore(), truncated, duration);
            while (bytes(result) > 1024 * 1024 && !rows.isEmpty()) {
                rows.removeLast();
                truncated = true;
                result = new DataQueryResultView(input.collectionId(), input.generation(), latest.fields(),
                    List.copyOf(rows), true, true, duration);
            }
            if (truncated && rows.isEmpty()) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_RESULT_TOO_LARGE",
                    "结果超过大小限制，请减少返回字段。");
            }
            audit.record(actor.enterpriseId(), actor.user(), "data.query", "data_collection", input.collectionId(),
                "读取数据集合",
                Map.of("generation", input.generation(), "fields", input.fields(), "rowCount", rows.size(),
                    "durationMs", duration));
            return result;
        } catch (QueryTimeoutException expired) {
            throw timeout();
        }
    }

    public void authorizeResult(AuthContext actor, String resource, DataQueryRequest input, JsonNode resultFields) {
        policy.use(actor, resource);
        var allowed = plan(actor, resource, input, false);
        for (var field : allowed.fields()) {
            if (field.sensitive()) {
                boolean hidden = false;
                for (var saved : resultFields) {
                    if (field.name().equals(saved.path("name").asText()) && saved.path("sensitive").asBoolean()) {
                        hidden = true;
                    }
                }
                if (!hidden) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "DATA_RESULT_PERMISSION_CHANGED",
                        "此结果包含现在已限制的字段，请重新查询后查看。");
                }
            }
        }
    }

    private DataQueryPlan plan(AuthContext actor, String resource, DataQueryRequest input, boolean newQuery) {
        var collection = policy.collection(actor.enterpriseId(), resource, input.collectionId(), false);
        if (newQuery && input.generation() != collection.activeGeneration()) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_GENERATION_CHANGED",
                "数据集合已经更新，请刷新集合后重新查询。");
        }
        if (!collections.hasGeneration(actor.enterpriseId(), collection.id(), input.generation())) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_GENERATION_UNAVAILABLE",
                "所选集合版本已经不可查询，请重新选择。");
        }
        return validation.plan(collection,
            collections.fields(actor.enterpriseId(), collection.id(), input.generation()),
            collections.fields(actor.enterpriseId(), collection.id(), collection.activeGeneration()), input);
    }

    private int bytes(Object value) {
        try {
            return json.writeValueAsBytes(value).length;
        } catch (Exception invalid) {
            throw new IllegalStateException("数据查询结果无法编码", invalid);
        }
    }

    private ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "DATA_QUERY_TIMEOUT",
            "数据查询超过允许时间，请减少查询范围。");
    }
}
