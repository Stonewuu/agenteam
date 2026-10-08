package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.data.HttpDataResponseMapper;
import com.stonewu.agenteam.mapper.data.MysqlSourceSchema;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.request.DataCollectionWriteRequest;
import com.stonewu.agenteam.model.data.response.DataCollectionView;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.data.http.HttpDataSourceReader;
import com.stonewu.agenteam.service.data.mysql.MysqlReadOnlyConnections;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 远程集合先验证实际结构，再保存明确允许的字段；历史版本不能改为另一张表。
 */
@Service
public class DataRemoteCollectionService {
    private final DataResourcePolicy policy;
    private final DataCollectionMapper collections;
    private final MysqlReadOnlyConnections mysql;
    private final MysqlSourceSchema schema;
    private final DataSourceHash sourceHash;
    private final AuditEventService audit;
    private final Clock clock;
    private final HttpDataSourceReader http;
    private final HttpDataResponseMapper httpMapping;

    public DataRemoteCollectionService(DataResourcePolicy policy, DataCollectionMapper collections,
                                       MysqlReadOnlyConnections mysql, MysqlSourceSchema schema,
                                       DataSourceHash sourceHash, AuditEventService audit, Clock clock,
                                       HttpDataSourceReader http, HttpDataResponseMapper httpMapping) {
        this.policy = policy;
        this.collections = collections;
        this.mysql = mysql;
        this.schema = schema;
        this.sourceHash = sourceHash;
        this.audit = audit;
        this.clock = clock;
        this.http = http;
        this.httpMapping = httpMapping;
    }

    public void inspect(ResourceRecord resource, DataCollectionWriteRequest input, List<DataField> fields) {
        ResourceInput.text(input.name(), "name", 80, true);
        long deadline = System.nanoTime() + Math.min(10,
            resource.config().path("timeoutSeconds").asInt(10)) * 1_000_000_000L;
        if (resource.config().path("sourceType").asText().equals("http")) {
            var response = http.read(resource.enterpriseId(), resource.config(), List.of(), deadline);
            httpMapping.rows(response, input.sourceName(), fields, deadline);
            return;
        }
        if (!resource.config().path("sourceType").asText().equals("mysql")) {
            throw ApiException.invalidField("sourceName", "文件集合请使用导入。");
        }
        try (var session = mysql.open(resource.enterpriseId(), resource.config(), deadline)) {
            schema.require(session, input.sourceName(), fields);
        } catch (ApiException invalid) {
            throw invalid;
        } catch (Exception unavailable) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_SOURCE_SCHEMA_UNAVAILABLE",
                "暂时无法读取所选库表和字段，请稍后重新检查。");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DataCollectionView save(AuthContext actor, ResourceRecord inspected, String collectionId, Long revision,
                                   DataCollectionWriteRequest input, List<DataField> fields) {
        var resource = policy.edit(actor, inspected.id(), true);
        if (resource.revision() != inspected.revision() || !resource.configHash().equals(inspected.configHash())) {
            throw ApiException.versionConflict(resource.revision());
        }
        var current = collectionId == null ? null : policy.collection(actor.enterpriseId(), resource.id(), collectionId,
            true);
        if (current != null) {
            policy.revision(current, revision);
            if (!current.sourceName().equals(input.sourceName())) {
                throw ApiException.invalidField("sourceName", "已有集合不能改为另一来源，请建立新的集合。");
            }
            if (current.activeGeneration() == Integer.MAX_VALUE) {
                throw new ApiException(HttpStatus.CONFLICT, "DATA_GENERATION_LIMIT",
                    "集合版本数量达到上限，请建立新集合。");
            }
        } else if (collections.all(actor.enterpriseId(), resource.id()).stream()
            .anyMatch(row -> row.sourceName().equals(input.sourceName()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_COLLECTION_EXISTS", "这个来源已经登记，请修改已有集合。");
        }
        String id = current == null ? UUID.randomUUID().toString() : current.id();
        int generation = current == null ? 1 : current.activeGeneration() + 1;
        String name = ResourceInput.text(input.name(), "name", 80, true);
        if (current == null) {
            collections.create(id, actor.enterpriseId(), resource.id(), name, input.sourceName(), null, null,
                clock.instant());
        }
        collections.generation(actor.enterpriseId(), id, generation, sourceHash.calculate(resource.config()), null,
            null, fields, clock.instant());
        if (current != null) {
            collections.activate(current, name, current.sourceName(), generation, null, null, clock.instant());
        }
        audit.record(actor.enterpriseId(), actor.user(), "data.collection.update", "data_collection", id,
            "保存远程数据集合与允许字段", Map.of("generation", generation));
        return DataCollectionMapper.view(collections.find(actor.enterpriseId(), id, false).orElseThrow(), fields);
    }
}
