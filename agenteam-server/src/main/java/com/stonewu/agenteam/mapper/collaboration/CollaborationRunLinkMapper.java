package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import org.apache.ibatis.annotations.Mapper;

/** 岗位轮次与现有执行之间的唯一关联的类型化数据库访问。 */
@Mapper
public interface CollaborationRunLinkMapper extends MPJBaseMapper<CollaborationRunLinkRow> {
}
