package com.stonewu.agenteam.service.announcement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.announcement.*;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementPublicationRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementReadRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.model.announcement.response.AnnouncementPageView;
import com.stonewu.agenteam.model.announcement.response.AnnouncementUnreadView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 已读保存在服务端，并绑定实际展示的内容版本，跨设备和企业切换仍有效。
 */
@Service
public class AnnouncementUserService {
    private final AnnouncementQueryMapper queries;
    private final AnnouncementSqlMapper announcements;
    private final AnnouncementPublicationMapper publications;
    private final AnnouncementReadMapper reads;
    private final AnnouncementViewMapper views;
    private final AnnouncementLevelCatalog levels;
    private final ListPagination pagination;
    private final Clock clock;

    public AnnouncementUserService(AnnouncementQueryMapper queries, AnnouncementSqlMapper announcements,
                                   AnnouncementPublicationMapper publications, AnnouncementReadMapper reads,
                                   AnnouncementViewMapper views,
                                   AnnouncementLevelCatalog levels, ListPagination pagination, Clock clock) {
        this.queries = queries;
        this.announcements = announcements;
        this.publications = publications;
        this.reads = reads;
        this.views = views;
        this.levels = levels;
        this.pagination = pagination;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnouncementPageView list(AuthContext actor, boolean unread, String cursor, Integer size) {
        int limit = pagination.limit(size);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "announcements",
            Boolean.toString(unread), "scope_level_publication_desc");
        var position = pagination.read(cursor, binding);
        long through = queries.through(actor);
        AnnouncementQueryMapper.Position before = null;
        if (position != null) {
            var parts = position.sortValue().split(":");
            if (parts.length != 4) {
                throw ApiException.invalidField("cursor", "请重新打开公告列表。");
            }
            before = new AnnouncementQueryMapper.Position(parts[0], Integer.parseInt(parts[1]),
                Long.parseLong(parts[2]), Long.parseLong(parts[3]));
            through = before.through();
        }
        long boundary = through;
        var page = pagination.page(queries.page(actor, unread, through, before, limit + 1, null), limit, binding,
            row -> new PagePosition(Instant.EPOCH, row.getId(),
                row.getScope() + ":" + row.getLevelPriority() + ":" + row.getPublicationSequence() + ":" + boundary));
        return new AnnouncementPageView(views.views(page.items()), page.nextCursor(), page.hasMore(),
            Long.toString(through));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnouncementUnreadView unread(AuthContext actor) {
        long through = queries.through(actor);
        var next = queries.page(actor, true, through, null, 1, levels.popupLevels()).stream().findFirst()
            .map(views::view).orElse(null);
        return new AnnouncementUnreadView(queries.unread(actor), Long.toString(through), next);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnnouncementUnreadView read(AuthContext actor, String id, long version) {
        var row = announcements.lock(id);
        if (row == null || row.getDeletedAt() != null || !row.getEnabled() || (!"platform".equals(
            row.getScope()) && !actor.enterpriseId().equals(row.getEnterpriseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ANNOUNCEMENT_UNAVAILABLE", "此公告已停用或无法访问。");
        }
        if (row.getContentVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "ANNOUNCEMENT_CONTENT_CHANGED",
                "公告内容已更新，请阅读最新内容。");
        }
        reads.saveRead(List.of(readRow(actor, row)));
        return unread(actor);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnnouncementUnreadView readAll(AuthContext actor, long through) {
        long latest = publications.selectPage(new Page<AnnouncementPublicationRow>(1, 1, false),
                Wrappers.<AnnouncementPublicationRow>lambdaQuery().orderByDesc(AnnouncementPublicationRow::getSequenceNo))
            .getRecords().stream().mapToLong(AnnouncementPublicationRow::getSequenceNo).findFirst().orElse(0);
        if (through < 0 || through > latest) {
            throw ApiException.invalidField("throughSequence", "请重新读取公告列表。");
        }
        while (true) {
            var pending = queries.page(actor, true, through, null, 100, null);
            if (pending.isEmpty()) {
                break;
            }
            reads.saveRead(pending.stream().map(row -> readRow(actor, row)).toList());
        }
        return unread(actor);
    }

    private AnnouncementReadRow readRow(AuthContext actor, AnnouncementRow announcement) {
        var row = new AnnouncementReadRow();
        row.setAnnouncementId(announcement.getId());
        row.setUserId(actor.userId());
        row.setContentVersion(announcement.getContentVersion());
        row.setReadAt(clock.instant());
        return row;
    }
}
