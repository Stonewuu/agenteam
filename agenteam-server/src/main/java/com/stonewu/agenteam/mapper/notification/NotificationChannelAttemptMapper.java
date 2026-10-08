package com.stonewu.agenteam.mapper.notification;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelAttemptRow;
import org.apache.ibatis.annotations.Mapper;

/** 每次真正发送请求的追加记录的数据库访问。 */
@Mapper
public interface NotificationChannelAttemptMapper extends MPJBaseMapper<NotificationChannelAttemptRow> {
}
