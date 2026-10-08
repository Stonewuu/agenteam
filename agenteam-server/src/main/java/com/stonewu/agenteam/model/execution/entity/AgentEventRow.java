package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_event 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_event")
public class AgentEventRow {
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "stream_id")
    private String streamId;

    @TableField(value = "conversation_id")
    private String conversationId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "sequence_no")
    private Long sequenceNo;

    @TableField(value = "conversation_sequence")
    private Long conversationSequence;

    @TableField(value = "protocol_version")
    private Integer protocolVersion;

    @TableField(value = "storage_version")
    private Integer storageVersion;

    @TableField(value = "event_type")
    private String eventType;

    @TableField(value = "payload_json")
    private String payloadJson;

    @TableField(value = "payload_hash")
    private String payloadHash;

    @TableField(value = "published_at")
    private Instant publishedAt;

    @TableField(value = "started_at")
    private Instant startedAt;

    @TableField(value = "finished_at")
    private Instant finishedAt;

    @TableField(value = "created_at")
    private Instant createdAt;
}
