package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 本人在一个来源会话中的回复偏好，不影响其他成员。 */
@Getter
@Setter
@TableName("chat_reply_preference")
public class ChatReplyPreferenceRow implements EnterpriseScopedRecord {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String userId;
    private String conversationId;
    private String outputVisibility;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
