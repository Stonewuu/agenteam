package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import org.apache.ibatis.annotations.Mapper;

/** 会话内每个机器人的实际地址与资格的类型化数据库访问。 */
@Mapper
public interface ChatConversationBotMapper extends MPJBaseMapper<ChatConversationBotRow> {
}
