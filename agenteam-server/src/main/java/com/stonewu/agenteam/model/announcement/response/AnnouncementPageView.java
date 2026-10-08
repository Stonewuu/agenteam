package com.stonewu.agenteam.model.announcement.response;

import java.util.List;

/**
 * 用户可见公告及本次读取的最大发布序号。
 */
public record AnnouncementPageView(List<AnnouncementView> items, String nextCursor, boolean hasMore,
                                   String throughSequence) {
}
