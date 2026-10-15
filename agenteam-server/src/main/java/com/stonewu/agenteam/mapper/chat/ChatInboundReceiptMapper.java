package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import org.apache.ibatis.annotations.Mapper;

/** 先保存后确认的消息接收记录的类型化数据库访问。 */
@Mapper
public interface ChatInboundReceiptMapper extends MPJBaseMapper<ChatInboundReceiptRow> {
}
