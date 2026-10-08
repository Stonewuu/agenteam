package com.stonewu.agenteam.model.announcement.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 公告个人已读记录，仅供数据库读写使用。
 */
@Getter
@Setter
@TableName("announcement_read")
public class AnnouncementReadRow {
    @TableField("announcement_id")
    private String announcementId;

    @TableField("user_id")
    private String userId;

    @TableField("content_version")
    private Long contentVersion;

    @TableField("read_at")
    private Instant readAt;

}
