package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import org.apache.ibatis.annotations.Mapper;

/** 外部群聊或私聊及明确启用范围的类型化数据库访问。 */
@Mapper
public interface ChatConversationMapper extends MPJBaseMapper<ChatConversationRow> {
}
