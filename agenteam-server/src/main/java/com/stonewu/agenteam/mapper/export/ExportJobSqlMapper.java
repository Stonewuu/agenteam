package com.stonewu.agenteam.mapper.export;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;

import org.apache.ibatis.annotations.Mapper;


/**
 * ExportJobMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ExportJobSqlMapper extends MPJBaseMapper<BackgroundJobRow> {
    default int resultBackgroundJob(String file, String payload, String enterpriseId, String id, String ownerUserId,
                                    String leaseOwner, long leaseVersion) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterpriseId)
            .eq(BackgroundJobRow::getId, id).eq(BackgroundJobRow::getOwnerUserId, ownerUserId)
            .eq(BackgroundJobRow::getKind, "export").eq(BackgroundJobRow::getStatus, "leased")
            .eq(BackgroundJobRow::getLeaseOwner, leaseOwner).eq(BackgroundJobRow::getLeaseVersion, leaseVersion)
            .set(BackgroundJobRow::getResultFileId, file).set(BackgroundJobRow::getPayloadJson, payload));
    }
}
