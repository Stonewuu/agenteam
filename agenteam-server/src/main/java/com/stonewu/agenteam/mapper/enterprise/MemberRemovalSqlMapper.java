package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalQueryRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * MemberRemovalMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface MemberRemovalSqlMapper extends MPJBaseMapper<ResourceRow> {


    default List<MemberRemovalQueryRow> resourcesResource(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getId, ResourceRow::getKind,
                ResourceRow::getRevision).orderByAsc(ResourceRow::getId).eq(ResourceRow::getEnterpriseId, enterprise)
            .eq(ResourceRow::getOwnerUserId, user).isNull(ResourceRow::getDeletedAt);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new MemberRemovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getKind() != null) {
                mappedRow.setKind(storedRow.getKind());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }


    default int transferResourcesResource(String recipient, Timestamp now, String enterprise, String user) {
        return update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterprise)
            .eq(ResourceRow::getOwnerUserId, user).isNull(ResourceRow::getDeletedAt)
            .set(ResourceRow::getOwnerUserId, recipient).setIncrBy(ResourceRow::getRevision, 1)
            .set(ResourceRow::getUpdatedAt, now));
    }


}
