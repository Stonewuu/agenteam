package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 本轮不可变的任务背景与成果来源；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_context")
public class CollaborationContextRow implements EnterpriseScopedRecord {
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

    @TableField("task_id")
    private String taskId;

    @TableField("work_id")
    private String workId;

    @TableField("run_round")
    private Integer runRound;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("schema_version")
    private Integer schemaVersion;

    @TableField("content_encrypted")
    private String contentEncrypted;

    @TableField("content_hash")
    private String contentHash;

    @TableField("source_refs_json")
    private String sourceRefsJson;

    @TableField("size_chars")
    private Integer sizeChars;

    @TableField("omitted_message_count")
    private Integer omittedMessageCount;
}
