package com.stonewu.agenteam.mapper.collaboration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 已经持久保存、可向房间发起人展示的消息。 */
@Mapper
public interface CollaborationRoomEntryMapper extends MPJBaseMapper<CollaborationRoomEntryRow> {
    List<CollaborationRoomEntryRow> readyHeads(int limit);
}
