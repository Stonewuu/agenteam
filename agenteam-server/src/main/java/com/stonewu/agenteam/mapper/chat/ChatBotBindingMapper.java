package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import org.apache.ibatis.annotations.Mapper;

/** 平台机器人的智能体绑定与连接租约的类型化数据库访问。 */
@Mapper
public interface ChatBotBindingMapper extends MPJBaseMapper<ChatBotBindingRow> {
}
