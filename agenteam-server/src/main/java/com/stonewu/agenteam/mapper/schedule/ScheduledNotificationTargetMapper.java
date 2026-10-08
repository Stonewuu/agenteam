package com.stonewu.agenteam.mapper.schedule;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.schedule.entity.ScheduledNotificationTargetRow;
import org.apache.ibatis.annotations.Mapper;

/** 定时通知接收人和额外渠道的类型化数据库访问。 */
@Mapper
public interface ScheduledNotificationTargetMapper extends MPJBaseMapper<ScheduledNotificationTargetRow> {
}
