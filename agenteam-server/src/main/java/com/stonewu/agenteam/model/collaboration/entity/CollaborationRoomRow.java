package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 用户创建的网页协作对话。 */
@Getter
@Setter
@TableName("collaboration_room")
public class CollaborationRoomRow implements EnterpriseScopedRecord {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
    private String ownerUserId;
    private String title;
    private String currentTaskId;
    private Long nextSequence;
}
