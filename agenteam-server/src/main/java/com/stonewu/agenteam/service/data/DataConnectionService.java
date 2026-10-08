package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.data.HttpDataResponseMapper;
import com.stonewu.agenteam.mapper.data.MysqlSourceSchema;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.response.ConnectionCheckView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.data.http.HttpDataSourceReader;
import com.stonewu.agenteam.service.data.mysql.MysqlReadOnlyConnections;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接检查只读真实结构，返回后重新核对草稿，不能用旧检查结果覆盖新连接。
 */
@Service
public class DataConnectionService {
    private static final Logger LOG = LoggerFactory.getLogger(DataConnectionService.class);

    public record Check(String startedAt, ConnectionCheckView view) {
    }

    private final DataResourcePolicy policy;
    private final DataCollectionMapper collections;
    private final MysqlReadOnlyConnections mysql;
    private final MysqlSourceSchema schema;
    private final ResourceMapper resources;
    private final ResourceJson json;
    private final ResourceConfigurationService configurations;
    private final AuditEventService audit;
    private final Clock clock;
    private final HttpDataSourceReader http;
    private final HttpDataResponseMapper httpMapping;

    public DataConnectionService(DataResourcePolicy policy, DataCollectionMapper collections,
                                 MysqlReadOnlyConnections mysql, MysqlSourceSchema schema,
                                 ResourceMapper resources, ResourceJson json,
                                 ResourceConfigurationService configurations, AuditEventService audit, Clock clock,
                                 HttpDataSourceReader http, HttpDataResponseMapper httpMapping) {
        this.policy = policy;
        this.collections = collections;
        this.mysql = mysql;
        this.schema = schema;
        this.resources = resources;
        this.json = json;
        this.configurations = configurations;
        this.audit = audit;
        this.clock = clock;
        this.http = http;
        this.httpMapping = httpMapping;
    }

    public ResourceRecord prepare(AuthContext actor, String resource, long revision) {
        var current = policy.edit(actor, resource, false);
        if (current.revision() != revision) {
            throw ApiException.versionConflict(current.revision());
        }
        configurations.use(actor, ResourceKind.DATA, current.config());
        return current;
    }

    public Check inspect(ResourceRecord resource) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("数据源连接检查不能占用数据库事务");
        }
        long start = System.nanoTime(), deadline = start + Math.min(10,
            resource.config().path("timeoutSeconds").asInt(10)) * 1_000_000_000L;
        String startedAt = clock.instant().toString();
        try {
            String summary;
            switch (resource.config().path("sourceType").asText()) {
                case "file" -> summary = "文件数据源无需外部连接，导入文件会单独检查。";
                case "mysql" -> {
                    try (var session = mysql.open(resource.enterpriseId(), resource.config(), deadline)) {
                        var configured = collections.all(resource.enterpriseId(), resource.id());
                        for (var collection : configured) {
                            schema.require(session, collection.sourceName(),
                                collections.fields(resource.enterpriseId(), collection.id(),
                                    collection.activeGeneration()));
                        }
                        summary = "数据库只读连接检查通过，已核对 " + configured.size() + " 个集合。";
                    }
                }
                case "http" -> {
                    var response = http.read(resource.enterpriseId(), resource.config(), List.of(), deadline);
                    var configured = collections.all(resource.enterpriseId(), resource.id());
                    for (var collection : configured) {
                        httpMapping.rows(response, collection.sourceName(),
                            collections.fields(resource.enterpriseId(), collection.id(), collection.activeGeneration()),
                            deadline);
                    }
                    summary = "接口读取检查通过，已核对 " + configured.size() + " 个集合。";
                }
                default ->
                    throw new ApiException(HttpStatus.CONFLICT, "DATA_SOURCE_UNAVAILABLE", "此数据连接暂时不能检查。");
            }
            return new Check(startedAt,
                new ConnectionCheckView(clock.instant().toString(), true, summary, elapsed(start), List.of()));
        } catch (ApiException failed) {
            LOG.warn("数据源连接检查失败，资源编号 {}", resource.id(), failed);
            return new Check(startedAt,
                new ConnectionCheckView(clock.instant().toString(), false, failed.getReason(), elapsed(start),
                    List.of()));
        } catch (Exception failed) {
            LOG.error("数据源连接检查失败，资源编号 {}", resource.id(), failed);
            return new Check(startedAt,
                new ConnectionCheckView(clock.instant().toString(), false, "数据连接或字段暂时无法读取，请检查配置。",
                    elapsed(start), List.of()));
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConnectionCheckView save(AuthContext actor, ResourceRecord inspected, Check check) {
        var current = policy.edit(actor, inspected.id(), true);
        if (current.revision() != inspected.revision() || !current.configHash().equals(inspected.configHash())) {
            throw ApiException.versionConflict(current.revision());
        }
        if (current.validation() != null && current.validation().at("/connection/startedAt").isTextual()
            && Instant.parse(current.validation().at("/connection/startedAt").asText())
            .isAfter(Instant.parse(check.startedAt()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CHECK_SUPERSEDED", "已有更新的检查结果，请重新加载。");
        }
        configurations.use(actor, ResourceKind.DATA, current.config());
        var connection = new LinkedHashMap<String, Object>();
        connection.put("startedAt", check.startedAt());
        connection.put("checkedAt", check.view().checkedAt());
        connection.put("status", check.view().success() ? "succeeded" : "failed");
        connection.put("summary", check.view().summary());
        connection.put("durationMs", check.view().durationMs());
        connection.put("toolChanges", List.of());
        resources.validation(actor.enterpriseId(), current.id(),
            json.tree(Map.of("configHash", current.configHash(), "connection", connection, "fieldErrors", Map.of())));
        audit.record(actor.enterpriseId(), actor.user(), "data.check", "resource", current.id(),
            "检查数据源连接与集合字段", Map.of("success", check.view().success(), "configHash", current.configHash()));
        return check.view();
    }

    private long elapsed(long started) {
        return Math.min(120000, Math.max(0, (System.nanoTime() - started) / 1_000_000));
    }
}
