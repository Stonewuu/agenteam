package com.stonewu.agenteam.mapper.file;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.MessageAttachmentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 附件批量绑定与会话删除时的文件保留时间更新。
 */
@Mapper
public interface MessageAttachmentSqlMapper extends MPJBaseMapper<MessageAttachmentRow> {

    int removeConversationAttachments(@Param("enterprise") String enterprise,
                                      @Param("conversation") String conversation);

    int expireDetachedFiles(@Param("enterprise") String enterprise, @Param("ids") List<String> ids,
                            @Param("now") Instant now);
}
