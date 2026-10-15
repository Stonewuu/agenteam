package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkDependencyRow;
import org.apache.ibatis.annotations.Mapper;

/** 工作启动或恢复需要完成的工作的类型化数据库访问。 */
@Mapper
public interface CollaborationWorkDependencyMapper extends MPJBaseMapper<CollaborationWorkDependencyRow> {
}
