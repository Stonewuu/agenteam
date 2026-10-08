package com.stonewu.agenteam.mapper.test.enterprise;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

/**
 * enterprise_team 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface EnterpriseTeamFixtureMapper extends MPJBaseMapper<EnterpriseTeamRow> {
    default int markDeleted(String id) {
        return update(new LambdaUpdateWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getId, id)
            .set(EnterpriseTeamRow::getDeletedAt, Instant.now()).set(EnterpriseTeamRow::getDeletedToken, id));
    }

}
