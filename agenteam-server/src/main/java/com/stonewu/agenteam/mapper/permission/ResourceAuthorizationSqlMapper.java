package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.permission.entity.ResourceAuthorizationQueryRow;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantRow;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ResourceAuthorizationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ResourceAuthorizationSqlMapper extends MPJBaseMapper<ResourceGrantRow> {
    List<ResourceAuthorizationQueryRow> findVisible(@Param("id") String id, @Param("scope") ResourceQueryScope scope);

    List<String> findVisibleIds(@Param("ids") List<String> ids, @Param("scope") ResourceQueryScope scope);

    List<ResourceAuthorizationQueryRow> lockResource(@Param("enterpriseId") String enterpriseId,
                                                     @Param("id") String id);

    default List<ResourceAuthorizationQueryRow> grantsResourceGrant(String enterpriseId, String id) {
        var criteria = new LambdaQueryWrapper<ResourceGrantRow>().select(ResourceGrantRow::getSubjectType,
                ResourceGrantRow::getSubjectId, ResourceGrantRow::getCapability)
            .orderByAsc(ResourceGrantRow::getSubjectType).orderByAsc(ResourceGrantRow::getSubjectId)
            .orderByAsc(ResourceGrantRow::getCapability).eq(ResourceGrantRow::getEnterpriseId, enterpriseId)
            .eq(ResourceGrantRow::getResourceId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new ResourceAuthorizationQueryRow();
            if (storedRow.getSubjectType() != null) {
                mappedRow.setSubjectType(storedRow.getSubjectType());
            }
            if (storedRow.getSubjectId() != null) {
                mappedRow.setSubjectId(storedRow.getSubjectId());
            }
            if (storedRow.getCapability() != null) {
                mappedRow.setCapability(storedRow.getCapability());
            }
            return mappedRow;
        }).toList();
    }

    default int replaceGrantsResourceGrant(String enterpriseId, String id, String subjectType, String subjectId,
                                           String capability) {
        return delete(new LambdaQueryWrapper<ResourceGrantRow>().eq(ResourceGrantRow::getEnterpriseId, enterpriseId)
            .eq(ResourceGrantRow::getResourceId, id).eq(ResourceGrantRow::getSubjectType, subjectType)
            .eq(ResourceGrantRow::getSubjectId, subjectId).eq(ResourceGrantRow::getCapability, capability));
    }

    default int addResourceGrant(String value, String enterpriseId, String id, String subjectType, String subjectId,
                                 String capability, String actorId, Timestamp now) {
        var databaseRow = new ResourceGrantRow();
        databaseRow.setId(value);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setResourceId(id);
        databaseRow.setSubjectType(subjectType);
        databaseRow.setSubjectId(subjectId);
        databaseRow.setCapability(capability);
        databaseRow.setCreatedBy(actorId);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


}
