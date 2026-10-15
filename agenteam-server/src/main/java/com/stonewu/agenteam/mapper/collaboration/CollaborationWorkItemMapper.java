package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import org.apache.ibatis.annotations.Mapper;

/** 可排队恢复的岗位工作的类型化数据库访问。 */
@Mapper
public interface CollaborationWorkItemMapper extends MPJBaseMapper<CollaborationWorkItemRow> {
}
