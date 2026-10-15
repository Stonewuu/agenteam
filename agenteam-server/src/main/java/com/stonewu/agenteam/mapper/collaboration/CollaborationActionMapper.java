package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import org.apache.ibatis.annotations.Mapper;

/** 与工具结果一起提交的协作事实的类型化数据库访问。 */
@Mapper
public interface CollaborationActionMapper extends MPJBaseMapper<CollaborationActionRow> {
}
