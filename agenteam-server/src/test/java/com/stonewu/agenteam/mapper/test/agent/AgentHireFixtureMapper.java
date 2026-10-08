package com.stonewu.agenteam.mapper.test.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.support.TestDatabaseValues;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * agent_hire 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface AgentHireFixtureMapper extends MPJBaseMapper<AgentHireRow> {
    default int workspaceApiDefaultEmployeeUsesRealUseTimeAndSkipsPausedOrInvalidEntriesBeforeLimitUpdate4(@Param("args") Object... args) {
        Instant databaseNow = Instant.now();
        return update(new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, args[0]).eq(AgentHireRow::getAgentId, args[1]).set(AgentHireRow::getStatus, "paused").set(AgentHireRow::getLastUsedAt, databaseNow));
    }

    default int resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate(@Param("args") Object... args) {
        Instant databaseNow = Instant.now();
        var databaseRow = new AgentHireRow();
        databaseRow.setId("hire-1");
        databaseRow.setEnterpriseId("first");
        databaseRow.setUserId("owner");
        databaseRow.setAgentId("first");
        databaseRow.setHiredAt(databaseNow);
        return insert(databaseRow);
    }

    default int resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate6(@Param("args") Object... args) {
        Instant databaseNow = Instant.now();
        var databaseRow = new AgentHireRow();
        databaseRow.setId("hire-2");
        databaseRow.setEnterpriseId("first");
        databaseRow.setUserId("owner");
        databaseRow.setAgentId("first");
        databaseRow.setHiredAt(databaseNow);
        return insert(databaseRow);
    }

    default int enterpriseTestDataConversationUpdate(@Param("args") Object... args) {
        var databaseRow = new AgentHireRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setUserId(TestDatabaseValues.string(args[2]));
        databaseRow.setAgentId(TestDatabaseValues.string(args[3]));
        databaseRow.setHiredAt(TestDatabaseValues.instant(args[4]));
        return insert(databaseRow);
    }

}
