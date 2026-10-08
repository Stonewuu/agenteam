package com.stonewu.agenteam.mapper.test.background;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.support.TestDatabaseValues;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * background_job 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface BackgroundJobFixtureMapper extends MPJBaseMapper<BackgroundJobRow> {
    default int organizationApiRetentionJobUpdate(@Param("args") Object... args) {
        var databaseRow = new BackgroundJobRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setOwnerUserId(TestDatabaseValues.string(args[2]));
        databaseRow.setKind("mail");
        databaseRow.setDedupeKey(TestDatabaseValues.string(args[3]));
        databaseRow.setPayloadJson("{}");
        databaseRow.setStatus(TestDatabaseValues.string(args[4]));
        databaseRow.setAvailableAt(TestDatabaseValues.instant(args[5]));
        databaseRow.setUpdatedAt(TestDatabaseValues.instant(args[6]));
        return insert(databaseRow);
    }

    default int expireLease(String id) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .set(BackgroundJobRow::getLeaseUntil, Instant.now().minusSeconds(1)));
    }

    List<String> knowledgeApiABatchBecomesSearchableOnlyAfterProcessingAndReturnsRealChineseSourcesObject(@Param("args") Object... args);

    List<String> scheduleRevocationApiDisablingAMemberPausesTheirPrivatePlanAndStopsTheAcceptedOccurrenceList(@Param("args") Object... args);

}
