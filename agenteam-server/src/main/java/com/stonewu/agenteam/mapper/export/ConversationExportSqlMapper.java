package com.stonewu.agenteam.mapper.export;

import com.stonewu.agenteam.model.export.entity.ConversationExportRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/**
 * 在映射调用结束前逐行交付结果，避免一次性保留全部消息。
 */
@Mapper
public interface ConversationExportSqlMapper {
    void readMessages(@Param("enterprise") String enterprise, @Param("user") String user,
                      @Param("conversation") String conversation, @Param("limit") int limit,
                      ResultHandler<ConversationExportRow> handler);
}
