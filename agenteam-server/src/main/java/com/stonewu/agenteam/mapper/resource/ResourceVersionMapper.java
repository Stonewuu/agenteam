package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.resource.entity.*;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发布只新增正文；撤销时只修改资格与撤销时间。
 */
@Repository
public class ResourceVersionMapper {
    private final ResourceVersionSqlMapper statements;
    private final ResourceJson json;
    private final ResourceDependencyTableMapper resourceDependencyTableMapper;

    public ResourceVersionMapper(ResourceVersionSqlMapper statements, ResourceJson json,
                                 ResourceDependencyTableMapper resourceDependencyTableMapper) {
        this.resourceDependencyTableMapper = resourceDependencyTableMapper;
        this.statements = statements;
        this.json = json;
    }

    public Optional<ResourceVersionRecord> find(String enterprise, String id) {
        return statements.selectJoinList(ResourceVersionQueryRow.class,
                versionQuery(enterprise).eq(ResourceVersionRow::getId, id))
            .stream().map(this::map).findFirst();
    }

    public Map<String, ResourceVersionRecord> findMany(String enterprise, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return statements.selectJoinList(ResourceVersionQueryRow.class,
                versionQuery(enterprise).in(ResourceVersionRow::getId, ids))
            .stream().map(this::map).collect(Collectors.toMap(ResourceVersionRecord::id, Function.identity()));
    }

    /**
     * 工具清单和实际调用均按当前资源状态过滤，不能仅信任已保存的执行配置。
     */
    public Set<String> available(String enterprise, List<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        var query = JoinWrappers.lambda(ResourceVersionRow.class).select(ResourceVersionRow::getId)
            .innerJoin(ResourceRow.class, on -> on.eq(ResourceRow::getEnterpriseId, ResourceVersionRow::getEnterpriseId)
                .eq(ResourceRow::getId, ResourceVersionRow::getResourceId))
            .eq(ResourceVersionRow::getEnterpriseId, enterprise).in(ResourceVersionRow::getId, ids)
            .eq(ResourceVersionRow::getStatus, "available").eq(ResourceRow::getStatus, "active")
            .isNull(ResourceRow::getDeletedAt);
        return statements.selectJoinList(ResourceVersionQueryRow.class, query).stream()
            .map(ResourceVersionQueryRow::getId).collect(Collectors.toSet());
    }

    public List<ResourceVersionRecord> list(String enterprise, String resource, PagePosition cursor, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("分页数量不能小于零");
        }
        var query = versionQuery(enterprise).eq(ResourceVersionRow::getResourceId, resource);
        if (cursor != null) {
            query.and(part -> part.lt(ResourceVersionRow::getPublishedAt, cursor.time())
                .or(equal -> equal.eq(ResourceVersionRow::getPublishedAt, cursor.time())
                    .lt(ResourceVersionRow::getId, cursor.id())));
        }
        query.orderByDesc(ResourceVersionRow::getPublishedAt, ResourceVersionRow::getId);
        return statements.selectJoinPage(new Page<ResourceVersionQueryRow>(1, (long) limit + 1, false),
                ResourceVersionQueryRow.class, query)
            .getRecords().stream().map(this::map).toList();
    }

    public void publish(String id, ResourceRecord resource, String note, String actor,
                        List<DependencyBinding> dependencies, Instant now) {
        ResourceVersionRow row = new ResourceVersionRow();
        row.setId(id);
        row.setEnterpriseId(resource.enterpriseId());
        row.setResourceId(resource.id());
        row.setVersionNo(resource.nextVersionNo());
        row.setName(resource.name());
        row.setDescription(resource.description());
        row.setConfigJson(json.write(resource.config()));
        row.setConfigHash(resource.configHash());
        row.setReleaseNote(note);
        row.setPublishedBy(actor);
        row.setPublishedAt(now);
        statements.insert(row);
        if (!dependencies.isEmpty()) {
            var rows = dependencies.stream().map(dependency -> {
                var link = new ResourceDependencyRow();
                link.setEnterpriseId(resource.enterpriseId());
                link.setParentVersionId(id);
                link.setDependencyVersionId(dependency.versionId());
                link.setBindingKey(dependency.bindingKey());
                link.setDependencyKind(dependency.kind());
                link.setOrdinal(dependency.ordinal());
                return link;
            }).toList();
            resourceDependencyTableMapper.insert(rows, 100);
        }
    }

    public List<DependencyBinding> dependencies(String enterprise, String version) {
        return resourceDependencyTableMapper.dependenciesResourceDependency(enterprise, version).stream().map(
            rows -> new DependencyBinding(rows.getDependencyVersionId(), rows.getDependencyKind(), rows.getBindingKey(),
                rows.getOrdinal())).toList();
    }

    public void revoke(String enterprise, String id, Instant now) {
        statements.revokeResourceVersion(Timestamp.from(now), enterprise, id);
    }

    private ResourceVersionRecord map(ResourceVersionQueryRow rows) {
        return new ResourceVersionRecord(rows.getId(), rows.getEnterpriseId(), rows.getResourceId(),
            rows.getVersionNo(),
            rows.getName(), rows.getDescription(), json.read(rows.getConfigJson()), rows.getConfigHash(),
            rows.getReleaseNote(), rows.getStatus(), rows.getPublishedBy(), rows.getPublishedByName(),
            rows.getPublishedAt());
    }

    private MPJLambdaWrapper<ResourceVersionRow> versionQuery(String enterprise) {
        return JoinWrappers.lambda(ResourceVersionRow.class).selectAll(ResourceVersionRow.class)
            .selectAs(EnterpriseMemberRow::getDisplayName, ResourceVersionQueryRow::getPublishedByName)
            .innerJoin(EnterpriseMemberRow.class, on -> on
                .eq(EnterpriseMemberRow::getEnterpriseId, ResourceVersionRow::getEnterpriseId)
                .eq(EnterpriseMemberRow::getUserId, ResourceVersionRow::getPublishedBy))
            .eq(ResourceVersionRow::getEnterpriseId, enterprise);
    }
}
