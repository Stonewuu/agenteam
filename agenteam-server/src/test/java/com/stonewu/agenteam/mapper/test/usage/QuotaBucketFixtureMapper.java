package com.stonewu.agenteam.mapper.test.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * quota_bucket 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface QuotaBucketFixtureMapper extends MPJBaseMapper<QuotaBucketRow> {
    List<Integer> conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(@Param("args") Object... args);

    List<Integer> executionApiTestSupportReservedObject(@Param("args") Object... args);

    default List<String> operationalMetricsApiQuotaMetricsOnlyCountCompletedChecksAndCommittedRepairsObject(@Param("args") Object... args) {
        var criteria = new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getId).orderByAsc(QuotaBucketRow::getId).eq(QuotaBucketRow::getEnterpriseId, args[0]);
        long pageSize = 1;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<QuotaBucketRow>(1, pageSize, false), criteria).getRecords().stream().map(storedRow -> storedRow.getId()).toList();
    }

    List<Integer> scheduleRetryApiUsedObject(@Param("args") Object... args);

}
