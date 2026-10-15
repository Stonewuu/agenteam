package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import org.apache.ibatis.annotations.Mapper;

/** 原发起人的协作总任务的类型化数据库访问。 */
@Mapper
public interface CollaborationTaskMapper extends MPJBaseMapper<CollaborationTaskRow> {
}
