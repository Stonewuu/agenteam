package com.stonewu.agenteam.mapper.security;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.security.entity.CredentialRow;
import com.stonewu.agenteam.model.security.entity.CredentialSummaryQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * CredentialSummaryMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface CredentialSummarySqlMapper extends MPJBaseMapper<CredentialRow> {
    List<CredentialSummaryQueryRow> listSummaries(@Param("enterprise") String enterprise,
                                                  @Param("cursor") PagePosition cursor, @Param("limit") int limit);

    List<CredentialSummaryQueryRow> findResource(@Param("enterprise") String enterprise, @Param("id") String id,
                                                 @Param("lock") boolean lock);

    default int revokeCredential(String actor, Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<CredentialRow>().eq(CredentialRow::getEnterpriseId, enterprise)
            .eq(CredentialRow::getId, id).eq(CredentialRow::getStatus, "active")
            .set(CredentialRow::getStatus, "revoked").setIncrBy(CredentialRow::getRevision, 1)
            .set(CredentialRow::getUpdatedBy, actor).set(CredentialRow::getUpdatedAt, now));
    }
}
