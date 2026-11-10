package com.stonewu.agenteam.mapper.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryFileRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import com.stonewu.agenteam.model.collaboration.entity.WebRoomFileView;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface CollaborationRoomEntryFileMapper extends MPJBaseMapper<CollaborationRoomEntryFileRow> {
    default List<String> fileIds(String enterprise, String entry) {
        return selectList(new LambdaQueryWrapper<CollaborationRoomEntryFileRow>().select(CollaborationRoomEntryFileRow::getFileId)
            .eq(CollaborationRoomEntryFileRow::getEnterpriseId, enterprise).eq(CollaborationRoomEntryFileRow::getEntryId, entry)
            .orderByAsc(CollaborationRoomEntryFileRow::getOrdinal)).stream().map(CollaborationRoomEntryFileRow::getFileId).toList();
    }

    default boolean readable(AuthContext actor, FileRecord file) {
        return (actor.superAdmin() || actor.permissions().contains("agent.run")) && selectJoinCount(JoinWrappers.lambda(CollaborationRoomEntryFileRow.class)
            .innerJoin(CollaborationRoomRow.class, on -> on.eq(CollaborationRoomRow::getEnterpriseId, CollaborationRoomEntryFileRow::getEnterpriseId)
                .eq(CollaborationRoomRow::getId, CollaborationRoomEntryFileRow::getRoomId))
            .eq(CollaborationRoomEntryFileRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationRoomRow::getOwnerUserId, actor.userId())
            .eq(CollaborationRoomEntryFileRow::getFileId, file.id()).eq(CollaborationRoomEntryFileRow::getSha256, file.sha256())) > 0;
    }

    default List<WebRoomFileView> forEntries(String enterprise, String user, Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return selectJoinList(WebRoomFileView.class, JoinWrappers.lambda(CollaborationRoomEntryFileRow.class)
            .selectAs(CollaborationRoomEntryFileRow::getEntryId, WebRoomFileView::getMessageId)
            .select(CollaborationRoomEntryRow::getTaskId)
            .select(FileObjectRow::getId, FileObjectRow::getOriginalName, FileObjectRow::getMediaType, FileObjectRow::getSizeBytes,
                FileObjectRow::getStatus, FileObjectRow::getErrorCode, FileObjectRow::getCreatedAt)
            .innerJoin(CollaborationRoomEntryRow.class, on -> on.eq(CollaborationRoomEntryRow::getEnterpriseId, CollaborationRoomEntryFileRow::getEnterpriseId)
                .eq(CollaborationRoomEntryRow::getId, CollaborationRoomEntryFileRow::getEntryId))
            .innerJoin(CollaborationRoomRow.class, on -> on.eq(CollaborationRoomRow::getEnterpriseId, CollaborationRoomEntryFileRow::getEnterpriseId)
                .eq(CollaborationRoomRow::getId, CollaborationRoomEntryFileRow::getRoomId))
            .innerJoin(FileObjectRow.class, on -> on.eq(FileObjectRow::getEnterpriseId, CollaborationRoomEntryFileRow::getEnterpriseId)
                .eq(FileObjectRow::getId, CollaborationRoomEntryFileRow::getFileId).eq(FileObjectRow::getSha256, CollaborationRoomEntryFileRow::getSha256))
            .eq(CollaborationRoomEntryFileRow::getEnterpriseId, enterprise).eq(CollaborationRoomRow::getOwnerUserId, user)
            .eq(FileObjectRow::getOwnerUserId, user).isNull(FileObjectRow::getDeletedAt).eq(FileObjectRow::getPurpose, "attachment")
            .in(CollaborationRoomEntryFileRow::getEntryId, ids).orderByAsc(CollaborationRoomEntryFileRow::getEntryId, CollaborationRoomEntryFileRow::getOrdinal));
    }
}
