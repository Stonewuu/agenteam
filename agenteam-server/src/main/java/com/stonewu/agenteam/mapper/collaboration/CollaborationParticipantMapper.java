package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import org.apache.ibatis.annotations.Mapper;

/** 任务中固定版本的岗位与独立会话的类型化数据库访问。 */
@Mapper
public interface CollaborationParticipantMapper extends MPJBaseMapper<CollaborationParticipantRow> {
}
