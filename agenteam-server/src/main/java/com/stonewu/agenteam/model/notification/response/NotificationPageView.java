package com.stonewu.agenteam.model.notification.response;

import java.util.List;

/**
 * 翻页继续使用打开列表时的通知范围，后来到达的提醒不会被全部已读误处理。
 */
public record NotificationPageView(List<NotificationView> items, String nextCursor, boolean hasMore,
                                   String throughSequence) {
}
