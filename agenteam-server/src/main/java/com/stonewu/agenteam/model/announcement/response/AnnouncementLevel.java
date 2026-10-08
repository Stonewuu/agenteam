package com.stonewu.agenteam.model.announcement.response;

/**
 * 公告提醒等级，新增等级只需扩展服务端目录。
 */
public record AnnouncementLevel(String code, String name, int priority, String tone, boolean popup) {
}
