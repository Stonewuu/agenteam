package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import org.apache.ibatis.annotations.Mapper;

/** 不可变版本的文字数据或文件成果的类型化数据库访问。 */
@Mapper
public interface CollaborationArtifactMapper extends MPJBaseMapper<CollaborationArtifactRow> {
}
