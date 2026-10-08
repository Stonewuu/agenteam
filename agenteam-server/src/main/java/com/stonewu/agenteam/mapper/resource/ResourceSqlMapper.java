package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceQueryRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * ResourceMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ResourceSqlMapper extends MPJBaseMapper<ResourceRow> {
    List<ResourceQueryRow> listResources(@Param("scope") ResourceQueryScope scope, @Param("query") String query,
                                         @Param("status") String status, @Param("source") String source,
                                         @Param("subtype") String subtype, @Param("tags") List<String> tags,
                                         @Param("sort") String sort, @Param("cursor") PagePosition cursor,
                                         @Param("limit") int limit, @Param("deletedAfter") Instant deletedAfter);

    default List<ResourceQueryRow> findResource(String enterprise, String id, boolean deleted, boolean lock) {
        if (lock) {
            return findResourceLocked(enterprise, id, deleted);
        }
        return selectJoinList(ResourceQueryRow.class, resourceQuery(enterprise, deleted).eq(ResourceRow::getId, id));
    }

    default List<ResourceQueryRow> findResources(String enterprise, List<String> ids, boolean deleted) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return selectJoinList(ResourceQueryRow.class, resourceQuery(enterprise, deleted).in(ResourceRow::getId, ids));
    }

    private MPJLambdaWrapper<ResourceRow> resourceQuery(String enterprise, boolean deleted) {
        var query = JoinWrappers.lambda(ResourceRow.class).selectAll(ResourceRow.class)
            .selectAs(EnterpriseMemberRow::getDisplayName, ResourceQueryRow::getOwnerDisplayName)
            .select(ResourceDraftRow::getConfigJson, ResourceDraftRow::getConfigHash,
                ResourceDraftRow::getValidationJson)
            .innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, ResourceRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, ResourceRow::getOwnerUserId))
            .leftJoin(ResourceDraftRow.class,
                on -> on.eq(ResourceDraftRow::getEnterpriseId, ResourceRow::getEnterpriseId)
                    .eq(ResourceDraftRow::getResourceId, ResourceRow::getId))
            .eq(ResourceRow::getEnterpriseId, enterprise);
        if (!deleted) {
            query.isNull(ResourceRow::getDeletedAt);
        }
        return query;
    }

    List<ResourceQueryRow> findResourceLocked(@Param("enterprise") String enterprise, @Param("id") String id,
                                              @Param("deleted") boolean deleted);

    default int createResource(String id, String enterprise, String code, String name, String description,
                               String subtype, String owner, String source, Timestamp now) {
        var databaseRow = new ResourceRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setKind(code);
        databaseRow.setName(name);
        databaseRow.setDescription(description);
        databaseRow.setSubtype(subtype);
        databaseRow.setOwnerUserId(owner);
        databaseRow.setSource(source);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


    default int draftResource(String name, String description, String subtype, Timestamp now, String enterpriseId,
                              String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision).set(ResourceRow::getName, name)
            .set(ResourceRow::getDescription, description).set(ResourceRow::getSubtype, subtype)
            .setIncrBy(ResourceRow::getRevision, 1).set(ResourceRow::getUpdatedAt, now));
    }


    default int publishResource(String version, Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision)
            .set(ResourceRow::getPublishedVersionId, version).setIncrBy(ResourceRow::getNextVersionNo, 1)
            .setIncrBy(ResourceRow::getRevision, 1).set(ResourceRow::getUpdatedAt, now));
    }

    default int statusResource(String status, Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision).set(ResourceRow::getStatus, status)
            .setIncrBy(ResourceRow::getRevision, 1).set(ResourceRow::getUpdatedAt, now));
    }

    default int markDeletedResource(Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id)
            .eq(ResourceRow::getRevision, revision).set(ResourceRow::getDeletedAt, now)
            .set(ResourceRow::getDeletedToken, id)
            .set(ResourceRow::getStatus, "deleted").setIncrBy(ResourceRow::getRevision, 1)
            .set(ResourceRow::getUpdatedAt, now));
    }

    default int restoreResource(Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision).set(ResourceRow::getStatus, "disabled")
            .set(ResourceRow::getDeletedAt, null).set(ResourceRow::getDeletedToken, "")
            .setIncrBy(ResourceRow::getRevision, 1).set(ResourceRow::getUpdatedAt, now));
    }

    default int ownerResource(String owner, Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision).set(ResourceRow::getOwnerUserId, owner)
            .setIncrBy(ResourceRow::getRevision, 1).set(ResourceRow::getUpdatedAt, now));
    }

    default int advanceRevisionResource(Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision).setIncrBy(ResourceRow::getRevision, 1)
            .set(ResourceRow::getUpdatedAt, now));
    }


    default int advanceAuthorizationRevision(Timestamp now, String enterpriseId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterpriseId)
            .eq(ResourceRow::getId, id).eq(ResourceRow::getRevision, revision).isNull(ResourceRow::getDeletedAt)
            .setIncrBy(ResourceRow::getRevision, 1).set(ResourceRow::getUpdatedAt, now));
    }
}
