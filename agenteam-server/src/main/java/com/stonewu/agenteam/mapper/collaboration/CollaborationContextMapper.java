package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationContextRow;
import org.apache.ibatis.annotations.Mapper;

/** 本轮不可变的任务背景与成果来源的类型化数据库访问。 */
@Mapper
public interface CollaborationContextMapper extends MPJBaseMapper<CollaborationContextRow> {
}
