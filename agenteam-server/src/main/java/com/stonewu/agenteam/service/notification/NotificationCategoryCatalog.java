package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 自动通知偏好只开放现有业务实际产生的类别，未知类别不自动发送到外部。 */
@Component
public class NotificationCategoryCatalog {
    private static final List<Category> CATEGORIES = List.of(new Category("execution", Messages.source("text.notification.notificationCategoryCatalog.CATEGORIES.1")),
        new Category("approval", Messages.source("text.notification.notificationCategoryCatalog.CATEGORIES.2")), new Category("permission", Messages.source("text.notification.notificationCategoryCatalog.CATEGORIES.3")),
        new Category("hire", Messages.source("text.notification.notificationCategoryCatalog.CATEGORIES.4")), new Category("todo", Messages.source("text.notification.notificationCategoryCatalog.CATEGORIES.5")), new Category("schedule", Messages.source("text.notification.notificationCategoryCatalog.CATEGORIES.6")));

    public List<Category> categories() {
        return CATEGORIES;
    }

    public boolean supports(String code) {
        return CATEGORIES.stream().anyMatch(value -> value.code().equals(code));
    }

    public void require(String code) {
        if (!supports(code)) {
            throw ApiException.invalidField("category", Messages.text("error.notification.notificationCategoryCatalog.require.1"));
        }
    }

    public Duration lifetime(String code) {
        require(code);
        return Duration.ofHours(24);
    }

    public boolean completionPreferenceApplies(NotificationRow notice) {
        return "execution".equals(notice.getCategory()) && notice.getEventKey().startsWith("run:")
            && notice.getEventKey().endsWith(":completed");
    }

    public record Category(String code, String name) {
    }
}
