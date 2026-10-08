package com.stonewu.agenteam.model.announcement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 公告发布记录，仅供数据库读写使用。
 */
@Getter
@Setter
@TableName("announcement_publication")
public class AnnouncementPublicationRow {
    @TableId(value = "sequence_no", type = IdType.AUTO)
    private Long sequenceNo;

    @TableField("announcement_id")
    private String announcementId;

    @TableField("content_version")
    private Long contentVersion;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("publisher_name")
    private String publisherName;

}
