package com.stonewu.agenteam.mapper.file;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 从已成功的文件调用读取会话关联路径，不把整个项目作为当前会话的产出。
 */
@Mapper
public interface ConversationWorkspaceFileMapper {
    List<String> modifiedPaths(@Param("enterprise") String enterprise, @Param("user") String user,
                               @Param("conversation") String conversation);
}
