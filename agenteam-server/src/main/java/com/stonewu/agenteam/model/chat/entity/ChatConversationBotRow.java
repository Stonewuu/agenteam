package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会话内每个机器人的实际地址与资格；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_conversation_bot")
public class ChatConversationBotRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("bot_binding_id")
    private String botBindingId;

    @TableField("external_chat_id")
    private String externalChatId;

    @TableField("membership_status")
    private String membershipStatus;

    @TableField("receive_enabled")
    private Boolean receiveEnabled;

    @TableField("send_enabled")
    private Boolean sendEnabled;

    @TableField("collaboration_enabled")
    private Boolean collaborationEnabled;

    @TableField("verified_at")
    private Instant verifiedAt;

    @TableField("verification_source")
    private String verificationSource;

    @TableField("last_error_code")
    private String lastErrorCode;
}
