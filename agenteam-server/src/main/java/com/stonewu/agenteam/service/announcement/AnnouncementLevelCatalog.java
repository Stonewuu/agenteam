package com.stonewu.agenteam.service.announcement;

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
        new AnnouncementLevel("urgent", "紧急", 300, "danger", true),
        new AnnouncementLevel("important", "重要", 200, "info", true),
        new AnnouncementLevel("general", "一般", 100, "neutral", false));

    public List<AnnouncementLevel> levels() {
        return LEVELS;
    }

    public AnnouncementLevel require(String code) {
        return LEVELS.stream().filter(level -> level.code().equals(code)).findFirst()
            .orElseThrow(() -> ApiException.invalidField("level", "请选择有效的提醒等级。"));
    }

    public List<String> popupLevels() {
        return LEVELS.stream().filter(AnnouncementLevel::popup).map(AnnouncementLevel::code).toList();
    }
}
