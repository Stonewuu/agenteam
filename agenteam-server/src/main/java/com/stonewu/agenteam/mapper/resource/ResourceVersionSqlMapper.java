package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;


/**
 * ResourceVersionMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ResourceVersionSqlMapper extends MPJBaseMapper<ResourceVersionRow> {


    default int revokeResourceVersion(Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getEnterpriseId, enterprise)
            .eq(ResourceVersionRow::getId, id).eq(ResourceVersionRow::getStatus, "available")
            .set(ResourceVersionRow::getStatus, "revoked").set(ResourceVersionRow::getRevokedAt, now));
    }
}
