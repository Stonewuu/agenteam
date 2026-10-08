package com.stonewu.agenteam.model.announcement.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 公告与当前用户对该版本的已读记录。
 */
@Getter
@Setter
public class AnnouncementQueryRow extends AnnouncementRow {
    private Instant readAt;
}
