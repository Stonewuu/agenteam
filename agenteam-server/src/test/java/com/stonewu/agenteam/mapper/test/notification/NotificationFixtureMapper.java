package com.stonewu.agenteam.mapper.test.notification;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * notification 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface NotificationFixtureMapper extends MPJBaseMapper<NotificationRow> {
    List<String> scheduleTriggerApiPausingTheHireImmediatelyPausesItsPlansAndDeliversEachOwnerNoticeOnceList7(@Param("args") Object... args);

    List<String> scheduleTriggerApiWaitingForApprovalBlocksTheNextOccurrenceAndExpirationReleasesThePlanList10(@Param("args") Object... args);

}
