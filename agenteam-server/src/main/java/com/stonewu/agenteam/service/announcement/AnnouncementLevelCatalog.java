package com.stonewu.agenteam.service.announcement;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.announcement.response.AnnouncementLevel;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 等级名称、顺序及提醒方式集中定义；前端根据返回的提醒方式展示新增等级。
 */
@Component
public class AnnouncementLevelCatalog {
    private static final List<AnnouncementLevel> LEVELS = List.of(
        new AnnouncementLevel("urgent", Messages.source("text.announcement.announcementLevelCatalog.LEVELS.1"), 300, "danger", true),
        new AnnouncementLevel("important", Messages.source("text.announcement.announcementLevelCatalog.LEVELS.2"), 200, "info", true),
        new AnnouncementLevel("general", Messages.source("text.announcement.announcementLevelCatalog.LEVELS.3"), 100, "neutral", false));

    public List<AnnouncementLevel> levels() {
        return LEVELS;
    }

    public AnnouncementLevel require(String code) {
        return LEVELS.stream().filter(level -> level.code().equals(code)).findFirst()
            .orElseThrow(() -> ApiException.invalidField("level", Messages.text("error.announcement.announcementWriteRequest.level.2")));
    }

    public List<String> popupLevels() {
        return LEVELS.stream().filter(AnnouncementLevel::popup).map(AnnouncementLevel::code).toList();
    }
}
