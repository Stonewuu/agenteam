package com.stonewu.agenteam.mapper.resource;


import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceQueryRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源主表与草稿一起读取；所有列表在排序和分页之前应用权限条件。
 */
@Repository
public class ResourceMapper {
    private final ResourceSqlMapper statements;
    private final ResourceJson json;
    private final ResourceDraftTableMapper resourceDraftTableMapper;

    public ResourceMapper(ResourceSqlMapper statements, ResourceJson json,
                          ResourceDraftTableMapper resourceDraftTableMapper) {
        this.resourceDraftTableMapper = resourceDraftTableMapper;
        this.statements = statements;
        this.json = json;
    }

    public Optional<ResourceRecord> find(String enterprise, String id, boolean deleted, boolean lock) {
        return statements.findResource(enterprise, id, deleted, lock).stream().map(this::map).findFirst();
    }

    public Map<String, ResourceRecord> findMany(String enterprise, List<String> ids, boolean deleted) {
        return statements.findResources(enterprise, ids, deleted).stream().map(this::map)
            .collect(Collectors.toMap(ResourceRecord::id, Function.identity()));
    }

    public List<ResourceRecord> list(ResourceQueryScope scope, String query, String status, String source,
                                     String subtype,
                                     List<String> tags, String sort, PagePosition cursor, int limit,
                                     Instant deletedAfter) {
        if (status != null) {
            switch (status) {
                case "draft", "published", "unlisted", "disabled", "deleted" -> {
                }
                default -> throw new IllegalArgumentException("不支持的资源状态");
            }
        }
        switch (sort) {
            case "created_desc", "name_asc", "updated_desc" -> {
            }
            default -> throw new IllegalArgumentException("不支持的资源排序");
        }
        return statements.listResources(scope, query, status, source, subtype, tags, sort, cursor, limit + 1,
                deletedAfter)
            .stream().map(this::map).toList();
    }

    public void create(String id, String enterprise, ResourceKind kind, String name, String description, String subtype,
                       String owner, String source, JsonNode config, Instant now) {
        statements.createResource(id, enterprise, kind.code(), name, description, subtype, owner, source,
            Timestamp.from(now));
        resourceDraftTableMapper.createResourceDraft(enterprise, id, json.write(config), json.hash(config), owner,
            Timestamp.from(now));
    }

    public void draft(ResourceRecord resource, String name, String description, String subtype, JsonNode config,
                      String actor, Instant now) {
        changed(statements.draftResource(name, description, subtype, Timestamp.from(now), resource.enterpriseId(),
            resource.id(), resource.revision()));
        resourceDraftTableMapper.draftResourceDraft(json.write(config), json.hash(config), actor, Timestamp.from(now),
            resource.enterpriseId(), resource.id());
    }

    public void publish(ResourceRecord resource, String version, Instant now) {
        changed(statements.publishResource(version, Timestamp.from(now), resource.enterpriseId(), resource.id(),
            resource.revision()));
    }

    public void status(ResourceRecord resource, String status, Instant now) {
        changed(statements.statusResource(status, Timestamp.from(now), resource.enterpriseId(), resource.id(),
            resource.revision()));
    }

    public void markDeleted(ResourceRecord resource, Instant now) {
        changed(statements.markDeletedResource(Timestamp.from(now), resource.enterpriseId(), resource.id(),
            resource.revision()));
    }

    public void restore(ResourceRecord resource, Instant now) {
        changed(statements.restoreResource(Timestamp.from(now), resource.enterpriseId(), resource.id(),
            resource.revision()));
    }

    public void owner(ResourceRecord resource, String owner, Instant now) {
        changed(statements.ownerResource(owner, Timestamp.from(now), resource.enterpriseId(), resource.id(),
            resource.revision()));
    }

    public void advanceRevision(ResourceRecord resource, Instant now) {
        changed(statements.advanceRevisionResource(Timestamp.from(now), resource.enterpriseId(), resource.id(),
            resource.revision()));
    }

    public void validation(String enterprise, String id, JsonNode value) {
        resourceDraftTableMapper.validationResourceDraft(value == null ? null : json.write(value), enterprise, id);
    }

    private void changed(int count) {
        if (count != 1) {
            throw new IllegalStateException("已锁定资源的修改版本发生变化");
        }
    }

    private ResourceRecord map(ResourceQueryRow rows) {
        var deleted = rows.getDeletedAt();
        return new ResourceRecord(rows.getId(), rows.getEnterpriseId(), ResourceKind.from(rows.getKind()),
            rows.getName(), rows.getDescription(), rows.getSubtype(), rows.getOwnerUserId(),
            rows.getOwnerDisplayName(), rows.getSource(), rows.getStatus(), rows.getPublishedVersionId(),
            rows.getNextVersionNo(), rows.getRevision(), rows.getCreatedAt().toInstant(),
            rows.getUpdatedAt().toInstant(),
            deleted == null ? null : deleted.toInstant(), json.read(rows.getConfigJson()), rows.getConfigHash(),
            json.read(rows.getValidationJson()));
    }
}
