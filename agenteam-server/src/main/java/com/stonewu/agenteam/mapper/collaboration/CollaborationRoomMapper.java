package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 用户创建的网页协作对话。 */
@Mapper
public interface CollaborationRoomMapper extends MPJBaseMapper<CollaborationRoomRow> {
    CollaborationRoomRow lockRoom(@Param("enterprise") String enterprise, @Param("room") String room);
}
