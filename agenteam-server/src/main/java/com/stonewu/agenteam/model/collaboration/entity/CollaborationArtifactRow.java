package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 不可变版本的文字数据或文件成果；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_artifact")
public class CollaborationArtifactRow implements EnterpriseScopedRecord {
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

    @TableField("producer_work_id")
    private String producerWorkId;

    @TableField("producer_run_id")
    private String producerRunId;

    @TableField("artifact_key")
    private String artifactKey;

    @TableField("artifact_version")
    private Integer artifactVersion;

    @TableField("kind")
    private String kind;

    @TableField("schema_name")
    private String schemaName;

    @TableField("title")
    private String title;

    @TableField("summary_encrypted")
    private String summaryEncrypted;

    @TableField("content_encrypted")
    private String contentEncrypted;

    @TableField("file_id")
    private String fileId;

    @TableField("sha256")
    private String sha256;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("visibility")
    private String visibility;

    @TableField("status")
    private String status;

    @TableField("supersedes_id")
    private String supersedesId;
}
