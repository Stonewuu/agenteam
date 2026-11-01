package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 问题固定到原执行和原成员，问题及回答加密保存。 */
@Getter
@Setter
@TableName("run_question")
public class RunQuestionRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String runId;
    private String toolCallId;
    private String sessionId;
    private String userId;
    private String requestHash;
    private String questionEncrypted;
    private String answerEncrypted;
    private String status;
    private String taskId;
    private String chatConversationId;
    private String botBindingId;
    private Instant freeTextSelectedAt;
    private Instant expiresAt;
    private Instant answeredAt;
    private Long revision;
    private Instant createdAt;
    private Instant updatedAt;
}
