package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 保存成员切换对话的时间，已经收到的消息仍归入切换前的对话。 */
@Getter
@Setter
@TableName("chat_session_selection")
public class ChatSessionSelectionRow implements EnterpriseScopedRecord {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String conversationId;
    private String userId;
    private String botBindingId;
    private String taskId;
    private Long selectionNo;
    private Long revision;
    private Instant selectedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
