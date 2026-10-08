package com.stonewu.agenteam.mapper.test.schedule;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.support.TestDatabaseValues;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * scheduled_occurrence 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ScheduledOccurrenceFixtureMapper extends MPJBaseMapper<ScheduledOccurrenceRow> {
    default int scheduleManagementApiInsertOccurrenceUpdate(@Param("args") Object... args) {
        var databaseRow = new ScheduledOccurrenceRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setScheduleId(TestDatabaseValues.string(args[2]));
        databaseRow.setTriggerKind("scheduled");
        databaseRow.setOccurrenceKey(TestDatabaseValues.string(args[3]));
        databaseRow.setScheduledFor(TestDatabaseValues.instant(args[4]));
        databaseRow.setStatus(TestDatabaseValues.string(args[5]));
        return insert(databaseRow);
    }

}
