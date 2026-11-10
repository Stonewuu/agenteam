package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 已经持久保存、可向房间发起人展示的消息。 */
@Getter
@Setter
@TableName("collaboration_room_entry")
public class CollaborationRoomEntryRow implements EnterpriseScopedRecord {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
    private String roomId;
    private String taskId;
    private String participantId;
    private String authorName;
    private String kind;
    private String sourceKey;
    private Long sequenceNo;
    private String deliveryState;
    private String recipientAgentId;
    private Long queuePosition;
    private String bodyEncrypted;
}
