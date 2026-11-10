package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 网页对话中受邀的智能体。 */
@Getter
@Setter
@TableName("collaboration_room_member")
public class CollaborationRoomMemberRow implements EnterpriseScopedRecord {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
    private String roomId;
    private String agentId;
    private String displayName;
    private Integer sortOrder;
}
