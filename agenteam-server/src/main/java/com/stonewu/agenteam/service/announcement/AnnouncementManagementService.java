package com.stonewu.agenteam.service.announcement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.announcement.AnnouncementPublicationMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementQueryMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementSqlMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementViewMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementPublicationRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.model.announcement.request.AnnouncementWriteRequest;
import com.stonewu.agenteam.model.announcement.response.AnnouncementManagementView;
import com.stonewu.agenteam.model.announcement.response.AnnouncementView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 平台入口只允许超级管理员；企业入口按实际企业管理员身份写入。
 */
@Service
public class AnnouncementManagementService {
    private final AnnouncementSqlMapper rows;
    private final AnnouncementPublicationMapper publications;
    private final AnnouncementQueryMapper queries;
    private final AnnouncementViewMapper views;
    private final AnnouncementLevelCatalog levels;
    private final AuthMapper users;
    private final PermissionMapper permissions;
    private final EnterpriseMapper enterprises;
    private final EnterpriseTableMapper enterpriseRows;
    private final EnterpriseAuthorizationService authorization;
    private final ListPagination pagination;
    private final AuditEventService audit;
    private final Clock clock;
    private final AnnouncementContent content;

    public AnnouncementManagementService(AnnouncementSqlMapper rows, AnnouncementPublicationMapper publications,
                                         AnnouncementQueryMapper queries, AnnouncementViewMapper views,
                                         AnnouncementLevelCatalog levels,
                                         AuthMapper users, PermissionMapper permissions, EnterpriseMapper enterprises,
                                         EnterpriseTableMapper enterpriseRows,
                                         EnterpriseAuthorizationService authorization, ListPagination pagination,
                                         AuditEventService audit, Clock clock, AnnouncementContent content) {
        this.rows = rows;
        this.publications = publications;
        this.queries = queries;
        this.views = views;
        this.levels = levels;
        this.users = users;
        this.permissions = permissions;
        this.enterprises = enterprises;
        this.enterpriseRows = enterpriseRows;
        this.authorization = authorization;
        this.pagination = pagination;
        this.audit = audit;
        this.clock = clock;
        this.content = content;
    }

    public void authorize(UserEntity actor, String enterprise, boolean system, boolean write) {
        var current = users.findById(actor.id()).orElseThrow(AnnouncementManagementService::denied);
        if (!current.status().equals("active") || current.sessionVersion() != actor.sessionVersion()) {
            throw denied();
        }
        if (system) {
            if (!current.superAdmin()) {
                throw denied();
            }
            if (enterprise != null) {
                var existing = enterpriseRows.selectById(enterprise);
                if (existing == null || !"active".equals(existing.getStatus())) {
                    throw missing();
                }
                if (write) {
                    enterprises.lockEnterprise(enterprise).filter("active"::equals)
                        .orElseThrow(AnnouncementManagementService::missing);
                }
            }
            return;
        }
        var context = new AuthContext(current, enterprise,
            Set.copyOf(permissions.listPermissionCodes(current.id(), enterprise)));
        if (write) {
            authorization.requireEnterpriseScope(authorization.lockAndRequire(context, "announcement.manage"));
            if (!current.superAdmin() && !permissions.isEnterpriseAdmin(current.id(), enterprise)) {
                throw denied();
            }
        } else {
            authorization.requireEnterpriseScope(authorization.require(context, "announcement.view"));
        }
    }

    public AnnouncementManagementView list(UserEntity actor, String enterprise, boolean system, String cursor,
                                           Integer size) {
        authorize(actor, enterprise, system, false);
        int limit = pagination.limit(size);
        var binding = new ListPagination.Binding(actor.id(), enterprise, "announcements/manage", "", "updated_desc");
        var page = pagination.page(queries.management(enterprise, pagination.read(cursor, binding), limit + 1), limit,
            binding,
            row -> new PagePosition(row.getUpdatedAt(), row.getId()));
        boolean canManage = actor.superAdmin() || permissions.isEnterpriseAdmin(actor.id(), enterprise);
        return new AnnouncementManagementView(views.views(page.items()), page.nextCursor(), page.hasMore(), canManage,
            levels.levels());
    }

    public record EnterpriseOption(String id, String name) {
    }

    public PageResponse<EnterpriseOption> enterpriseOptions(UserEntity actor, String query, String cursor,
                                                            Integer size) {
        authorize(actor, null, true, false);
        int limit = pagination.limit(size);
        String search = pagination.query(query);
        var binding = new ListPagination.Binding(actor.id(), null, "announcements/enterprises", search, "created_desc");
        var position = pagination.read(cursor, binding);
        var criteria = Wrappers.<EnterpriseRow>lambdaQuery().eq(EnterpriseRow::getStatus, "active")
            .like(!search.isEmpty(), EnterpriseRow::getName, search)
            .orderByDesc(EnterpriseRow::getCreatedAt, EnterpriseRow::getId);
        if (position != null) {
            criteria.and(next -> next.lt(EnterpriseRow::getCreatedAt, position.time())
                .or(same -> same.eq(EnterpriseRow::getCreatedAt, position.time())
                    .lt(EnterpriseRow::getId, position.id())));
        }
        var page = pagination.page(
            enterpriseRows.selectPage(new Page<EnterpriseRow>(1, limit + 1, false), criteria).getRecords(),
            limit, binding, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        return new PageResponse<>(
            page.items().stream().map(row -> new EnterpriseOption(row.getId(), row.getName())).toList(),
            page.nextCursor(), page.hasMore());
    }

    @Transactional
    public AnnouncementView create(UserEntity actor, String enterprise, boolean system,
                                   AnnouncementWriteRequest input) {
        authorize(actor, enterprise, system, true);
        var row = new AnnouncementRow();
        row.setId(UUID.randomUUID().toString());
        row.setScope(enterprise == null ? "platform" : "enterprise");
        row.setEnterpriseId(enterprise);
        row.setTitle(ResourceInput.text(input.title(), "title", 160, true));
        var body = content.validate(input.contentFormat(), input.content());
        row.setContent(body.content());
        row.setContentFormat(body.format());
        var level = levels.require(input.level());
        row.setLevelCode(level.code());
        row.setLevelPriority(level.priority());
        row.setEnabled(false);
        row.setContentVersion(1L);
        row.setRevision(1L);
        row.setCreatedBy(actor.id());
        row.setUpdatedBy(actor.id());
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        if (rows.insert(row) != 1) {
            throw new IllegalStateException("公告未能保存");
        }
        audit.record(enterprise, actor, "announcement.create", "announcement", row.getId(), "创建公告",
            Map.of("level", level.code()));
        return views.view(row);
    }

    @Transactional
    public AnnouncementView update(UserEntity actor, String enterprise, boolean system, String id, long revision,
                                   AnnouncementWriteRequest input) {
        authorize(actor, enterprise, system, true);
        var row = require(enterprise, id, revision);
        if (row.getEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "ANNOUNCEMENT_ENABLED", "请先停用公告，再修改内容。");
        }
        String title = ResourceInput.text(input.title(), "title", 160, true);
        var body = content.validate(input.contentFormat(), input.content());
        var level = levels.require(input.level());
        boolean changed = !title.equals(row.getTitle()) || !body.content().equals(row.getContent())
            || !body.format().equals(row.getContentFormat()) || !level.code().equals(row.getLevelCode());
        var update = Wrappers.<AnnouncementRow>lambdaUpdate().eq(AnnouncementRow::getId, id)
            .eq(AnnouncementRow::getRevision, revision)
            .eq(AnnouncementRow::getEnabled, false).isNull(AnnouncementRow::getDeletedAt)
            .set(AnnouncementRow::getTitle, title).set(AnnouncementRow::getContent, body.content())
            .set(AnnouncementRow::getContentFormat, body.format())
            .set(AnnouncementRow::getLevelCode, level.code()).set(AnnouncementRow::getLevelPriority, level.priority())
            .set(AnnouncementRow::getUpdatedBy, actor.id()).set(AnnouncementRow::getUpdatedAt, clock.instant())
            .setIncrBy(AnnouncementRow::getRevision, 1);
        if (changed) {
            update.setIncrBy(AnnouncementRow::getContentVersion, 1).set(AnnouncementRow::getPublicationSequence, null)
                .set(AnnouncementRow::getPublishedAt, null);
        }
        checkUpdate(rows.update(update));
        audit.record(enterprise, actor, "announcement.update", "announcement", id, "修改公告内容",
            Map.of("contentChanged", changed));
        return views.view(rows.selectById(id));
    }

    @Transactional
    public void delete(UserEntity actor, String enterprise, boolean system, String id, long revision) {
        authorize(actor, enterprise, system, true);
        require(enterprise, id, revision);
        var update = Wrappers.<AnnouncementRow>lambdaUpdate().eq(AnnouncementRow::getId, id)
            .eq(AnnouncementRow::getRevision, revision)
            .isNull(AnnouncementRow::getDeletedAt).set(AnnouncementRow::getDeletedAt, clock.instant())
            .set(AnnouncementRow::getEnabled, false)
            .set(AnnouncementRow::getUpdatedBy, actor.id()).set(AnnouncementRow::getUpdatedAt, clock.instant())
            .setIncrBy(AnnouncementRow::getRevision, 1);
        checkUpdate(rows.update(update));
        audit.record(enterprise, actor, "announcement.delete", "announcement", id, "删除公告", Map.of());
    }

    @Transactional
    public AnnouncementView status(UserEntity actor, String enterprise, boolean system, String id, long revision,
                                   boolean enabled) {
        authorize(actor, enterprise, system, true);
        var row = require(enterprise, id, revision);
        if (row.getEnabled() == enabled) {
            return views.view(row);
        }
        var update = Wrappers.<AnnouncementRow>lambdaUpdate().eq(AnnouncementRow::getId, id)
            .eq(AnnouncementRow::getRevision, revision)
            .isNull(AnnouncementRow::getDeletedAt).set(AnnouncementRow::getEnabled, enabled)
            .set(AnnouncementRow::getUpdatedBy, actor.id())
            .set(AnnouncementRow::getUpdatedAt, clock.instant()).setIncrBy(AnnouncementRow::getRevision, 1);
        if (enabled) {
            var published = publications.selectOne(Wrappers.<AnnouncementPublicationRow>lambdaQuery()
                .eq(AnnouncementPublicationRow::getAnnouncementId, id)
                .eq(AnnouncementPublicationRow::getContentVersion, row.getContentVersion()));
            if (published == null) {
                published = new AnnouncementPublicationRow();
                published.setAnnouncementId(id);
                published.setContentVersion(row.getContentVersion());
                published.setPublishedAt(clock.instant());
                published.setPublisherName(actor.displayName());
                checkUpdate(publications.insert(published));
            }
            update.set(AnnouncementRow::getPublicationSequence, published.getSequenceNo())
                .set(AnnouncementRow::getPublishedAt, published.getPublishedAt());
        }
        checkUpdate(rows.update(update));
        audit.record(enterprise, actor, enabled ? "announcement.enable" : "announcement.disable", "announcement", id,
            enabled ? "启用公告" : "停用公告", Map.of("version", row.getContentVersion()));
        return views.view(rows.selectById(id));
    }

    private AnnouncementRow require(String enterprise, String id, long revision) {
        var row = rows.lock(id);
        if (row == null || row.getDeletedAt() != null || !Objects.equals(enterprise, row.getEnterpriseId())) {
            throw missing();
        }
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        return row;
    }

    private void checkUpdate(int count) {
        if (count != 1) {
            throw new IllegalStateException("公告已变化，修改未能保存");
        }
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "ANNOUNCEMENT_MANAGEMENT_DENIED", "没有管理此范围公告的权限。");
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "ANNOUNCEMENT_NOT_FOUND", "公告或所属企业不存在或无法访问。");
    }
}
