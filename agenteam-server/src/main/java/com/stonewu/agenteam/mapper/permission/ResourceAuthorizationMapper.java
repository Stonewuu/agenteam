package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.permission.entity.ResourceAccessRecord;
import com.stonewu.agenteam.model.permission.entity.ResourceAuthorizationQueryRow;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 资源详情和列表共同使用已经限制企业、范围与直接授权的数据库条件。
 */
@Repository
public class ResourceAuthorizationMapper {
    private final ResourceAuthorizationSqlMapper statements;

    private final ResourceSqlMapper resourceSqlMapper;

    public ResourceAuthorizationMapper(ResourceAuthorizationSqlMapper statements, ResourceSqlMapper resourceSqlMapper) {
        this.resourceSqlMapper = resourceSqlMapper;
        this.statements = statements;
    }

    public Optional<ResourceAccessRecord> find(String enterpriseId, String id, boolean lock) {
        if (lock) {
            return statements.lockResource(enterpriseId, id).stream().map(this::map).findFirst();
        }
        return resourceSqlMapper.selectList(new LambdaQueryWrapper<ResourceRow>()
                .select(ResourceRow::getId, ResourceRow::getEnterpriseId, ResourceRow::getKind,
                    ResourceRow::getOwnerUserId, ResourceRow::getStatus, ResourceRow::getRevision)
                .eq(ResourceRow::getEnterpriseId, enterpriseId).eq(ResourceRow::getId, id)
                .isNull(ResourceRow::getDeletedAt))
            .stream().map(row -> new ResourceAccessRecord(row.getId(), row.getEnterpriseId(), row.getKind(),
                row.getOwnerUserId(), row.getStatus(), row.getRevision())).findFirst();
    }

    public Optional<ResourceAccessRecord> visible(String id, ResourceQueryScope scope) {
        return statements.findVisible(id, scope).stream().map(this::map).findFirst();
    }

    public Set<String> visibleIds(List<String> ids, ResourceQueryScope scope) {
        return ids.isEmpty() ? Set.of() : Set.copyOf(statements.findVisibleIds(ids, scope));
    }

    public List<ResourceGrantSpec> grants(String enterpriseId, String id) {
        return statements.grantsResourceGrant(enterpriseId, id).stream()
            .map(rows -> new ResourceGrantSpec(rows.getSubjectType(), rows.getSubjectId(), rows.getCapability()))
            .toList();
    }

    public void replaceGrants(String enterpriseId, String id, String actorId, Set<ResourceGrantSpec> requested,
                              Instant now) {
        var current = grants(enterpriseId, id);
        for (var grant : current) {
            if (!requested.contains(grant)) {
                statements.replaceGrantsResourceGrant(enterpriseId, id, grant.subjectType(), grant.subjectId(),
                    grant.capability());
            }
        }
        for (var grant : requested) {
            if (!current.contains(grant)) {
                statements.addResourceGrant(UUID.randomUUID().toString(), enterpriseId, id, grant.subjectType(),
                    grant.subjectId(), grant.capability(), actorId, Timestamp.from(now));
            }
        }
    }

    public void advanceRevision(String enterpriseId, String id, long revision, Instant now) {
        int changed = resourceSqlMapper.advanceAuthorizationRevision(Timestamp.from(now), enterpriseId, id, revision);
        if (changed != 1) {
            throw new IllegalStateException("资源已经变化，授权修改未提交");
        }
    }

    private ResourceAccessRecord map(ResourceAuthorizationQueryRow rows) {
        return new ResourceAccessRecord(rows.getId(), rows.getEnterpriseId(), rows.getKind(), rows.getOwnerUserId(),
            rows.getStatus(), rows.getRevision());
    }
}
