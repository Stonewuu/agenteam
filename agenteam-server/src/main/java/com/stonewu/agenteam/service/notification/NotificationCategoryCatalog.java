package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 自动通知偏好只开放现有业务实际产生的类别，未知类别不自动发送到外部。 */
@Component
public class NotificationCategoryCatalog {
    private static final List<Category> CATEGORIES = List.of(new Category("execution", "任务执行结果"),
        new Category("approval", "待确认操作"), new Category("permission", "任务访问变化"),
        new Category("hire", "员工申请结果"), new Category("todo", "待办分配"), new Category("schedule", "定时任务状态"));

    public List<Category> categories() {
        return CATEGORIES;
    }

    public boolean supports(String code) {
        return CATEGORIES.stream().anyMatch(value -> value.code().equals(code));
    }

    public void require(String code) {
        if (!supports(code)) {
            throw ApiException.invalidField("category", "请选择已有的通知类别。");
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
