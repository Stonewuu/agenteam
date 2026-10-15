package com.stonewu.agenteam.mapper.chat;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import org.apache.ibatis.annotations.Mapper;

/** 消息附件的受控下载及本地文件关联的类型化数据库访问。 */
@Mapper
public interface ChatMessageResourceMapper extends MPJBaseMapper<ChatMessageResourceRow> {
}
