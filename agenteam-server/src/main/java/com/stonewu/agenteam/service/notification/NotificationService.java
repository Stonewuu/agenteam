package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.notification.NotificationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.notification.response.NotificationPageView;
import com.stonewu.agenteam.model.notification.response.NotificationView;
import com.stonewu.agenteam.model.notification.response.UnreadCountView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 只处理本人通知，列表和读取范围从同一个数据库快照取得。
 */
@Service
public class NotificationService {
    private final NotificationMapper notifications;
    private final EnterpriseMapper enterprises;
    private final ListPagination pagination;
    private final Clock clock;

    public NotificationService(NotificationMapper notifications, EnterpriseMapper enterprises,
                               ListPagination pagination, Clock clock) {
        this.notifications = notifications;
        this.enterprises = enterprises;
        this.pagination = pagination;
        this.clock = clock;
    }

    public long authorize(AuthContext actor) {
        enterprises.lockEnterprise(actor.enterpriseId()).filter("active"::equals)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return sequence(actor, true);
    }

    public void require(AuthContext actor, String id) {
        notifications.find(actor.enterpriseId(), actor.userId(), id, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public NotificationView get(AuthContext actor, String id) {
        return notifications.find(actor.enterpriseId(), actor.userId(), id, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationPageView list(AuthContext actor, boolean unread, String cursor, Integer size) {
        long current = sequence(actor, false);
        int limit = pagination.limit(size);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "notifications",
            Boolean.toString(unread), "sequence_desc");
        var position = pagination.read(cursor, binding);
        Long before = null;
        long through = current;
        if (position != null) {
            try {
                String[] values = position.sortValue().split(":", -1);
                if (values.length != 2) {
                    throw invalidCursor();
                }
                before = Long.parseLong(values[0]);
                through = Long.parseLong(values[1]);
                if (before < 1 || through < before || through > current) {
                    throw invalidCursor();
                }
            } catch (NullPointerException | NumberFormatException invalid) {
                throw invalidCursor();
            }
        }
        final long openedThrough = through;
        var page = pagination.page(
            notifications.page(actor.enterpriseId(), actor.userId(), unread, through, before, limit), limit, binding,
            row -> new PagePosition(Instant.EPOCH, row.id(), row.sequence() + ":" + openedThrough));
        return new NotificationPageView(page.items(), page.nextCursor(), page.hasMore(), Long.toString(through));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UnreadCountView unread(AuthContext actor) {
        long through = sequence(actor, false);
        return count(actor, through);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public NotificationView read(AuthContext actor, String id) {
        authorize(actor);
        notifications.find(actor.enterpriseId(), actor.userId(), id, true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        notifications.read(actor.enterpriseId(), actor.userId(), id, clock.instant());
        return notifications.find(actor.enterpriseId(), actor.userId(), id, false).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UnreadCountView readAll(AuthContext actor, String value) {
        long current = authorize(actor);
        long through;
        try {
            through = Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw invalidSequence();
        }
        if (through < 0 || through > current) {
            throw invalidSequence();
        }
        notifications.readThrough(actor.enterpriseId(), actor.userId(), through, clock.instant());
        return count(actor, current);
    }

    private UnreadCountView count(AuthContext actor, long through) {
        return new UnreadCountView(notifications.unread(actor.enterpriseId(), actor.userId()), Long.toString(through));
    }

    private long sequence(AuthContext actor, boolean lock) {
        return notifications.sequence(actor.enterpriseId(), actor.userId(), lock)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    private ApiException invalidSequence() {
        return ApiException.invalidField("throughSequence", "请重新读取通知列表后再标记已读。");
    }

    private ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_CURSOR_INVALID", "通知列表已变化，请重新打开列表。");
    }
}
