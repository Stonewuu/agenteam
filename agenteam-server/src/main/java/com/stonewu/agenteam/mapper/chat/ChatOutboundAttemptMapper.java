package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundAttemptRow;
import org.apache.ibatis.annotations.Mapper;

/** 平台发送尝试及脱敏排障信息的类型化数据库访问。 */
@Mapper
public interface ChatOutboundAttemptMapper extends MPJBaseMapper<ChatOutboundAttemptRow> {
}
