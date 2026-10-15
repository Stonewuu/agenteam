package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import org.apache.ibatis.annotations.Mapper;

/** 独立于业务执行的群消息发送工作的类型化数据库访问。 */
@Mapper
public interface ChatOutboundMessageMapper extends MPJBaseMapper<ChatOutboundMessageRow> {
}
