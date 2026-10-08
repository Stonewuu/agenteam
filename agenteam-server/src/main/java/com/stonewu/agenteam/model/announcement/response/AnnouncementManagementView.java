package com.stonewu.agenteam.model.announcement.response;

import java.util.List;

/**
 * 公告管理页返回实际可执行操作及等级目录。
 */
public record AnnouncementManagementView(List<AnnouncementView> items, String nextCursor, boolean hasMore,
                                         boolean canManage, List<AnnouncementLevel> levels) {
}
