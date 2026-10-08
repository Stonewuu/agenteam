package com.stonewu.agenteam.model.announcement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 公告配置，仅供数据库读写使用。
 */
@Getter
@Setter
@TableName("announcement")
public class AnnouncementRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("scope")
    private String scope;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("content_format")
    private String contentFormat;

    @TableField("deleted_at")
    private Instant deletedAt;

    @TableField("level_code")
    private String levelCode;

    @TableField("level_priority")
    private Integer levelPriority;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("content_version")
    private Long contentVersion;

    @TableField("publication_sequence")
    private Long publicationSequence;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

}
