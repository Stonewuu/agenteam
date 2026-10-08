package com.stonewu.agenteam.mapper.test.usage;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaPolicyRow;
import com.stonewu.agenteam.support.TestDatabaseValues;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * quota_policy 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface QuotaPolicyFixtureMapper extends MPJBaseMapper<QuotaPolicyRow> {
    default int conversationSubmissionApiRoleLimitFailureDoesNotKeepEnterpriseReservationUpdate(@Param("args") Object... args) {
        var databaseRow = new QuotaPolicyRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setSubjectType("role");
        databaseRow.setSubjectId(TestDatabaseValues.string(args[2]));
        databaseRow.setMonthlyLimit(1L);
        return insert(databaseRow);
    }

}
