package com.stonewu.agenteam.model.announcement.response;

/**
 * 当前未读公告数量及下一条需要弹出的公告。
 */
public record AnnouncementUnreadView(long count, String throughSequence, AnnouncementView nextPopup) {
}
