package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import org.apache.ibatis.annotations.Mapper;

/** 去重后的会话消息及来源身份的类型化数据库访问。 */
@Mapper
public interface ChatMessageMapper extends MPJBaseMapper<ChatMessageRow> {
}
