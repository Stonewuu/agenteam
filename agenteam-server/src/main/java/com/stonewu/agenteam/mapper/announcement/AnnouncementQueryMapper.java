package com.stonewu.agenteam.mapper.announcement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementQueryRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementReadRow;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 只查询平台公告和当前企业公告，已读关联始终使用当前用户及当前内容版本。
 */
@Repository
public class AnnouncementQueryMapper {

    private final AnnouncementSqlMapper rows;

    public AnnouncementQueryMapper(AnnouncementSqlMapper rows) {
        this.rows = rows;
    }

    public record Position(String scope, int priority, long sequence, long through) {
    }

    public long through(AuthContext actor) {
        return rows.selectJoinPage(new Page<AnnouncementQueryRow>(1, 1, false), AnnouncementQueryRow.class,
                visible(actor, false).select(AnnouncementRow::getPublicationSequence)
                    .orderByDesc(AnnouncementRow::getPublicationSequence)).getRecords().stream()
            .mapToLong(AnnouncementRow::getPublicationSequence).findFirst().orElse(0);
    }

    public long unread(AuthContext actor) {
        return rows.selectJoinCount(visible(actor, true));
    }

    public Optional<AnnouncementQueryRow> find(AuthContext actor, String id) {
        return rows.selectJoinList(AnnouncementQueryRow.class,
            selectContent(visible(actor, false)).eq(AnnouncementRow::getId, id)).stream().findFirst();
    }

    public List<AnnouncementQueryRow> page(AuthContext actor, boolean unread, long through, Position before, int limit,
                                           List<String> levels) {
        var query = selectContent(visible(actor, unread)).le(AnnouncementRow::getPublicationSequence, through);
        if (levels != null) {
            query.in(AnnouncementRow::getLevelCode, levels);
        }
        if (before != null) {
            query.and(next -> next.lt(AnnouncementRow::getScope, before.scope())
                .or(sameScope -> sameScope.eq(AnnouncementRow::getScope, before.scope())
                    .lt(AnnouncementRow::getLevelPriority, before.priority()))
                .or(sameLevel -> sameLevel.eq(AnnouncementRow::getScope, before.scope())
                    .eq(AnnouncementRow::getLevelPriority, before.priority())
                    .lt(AnnouncementRow::getPublicationSequence, before.sequence())));
        }
        query.orderByDesc(AnnouncementRow::getScope, AnnouncementRow::getLevelPriority,
            AnnouncementRow::getPublicationSequence);
        return rows.selectJoinPage(new Page<AnnouncementQueryRow>(1, limit, false), AnnouncementQueryRow.class, query)
            .getRecords();
    }

    public List<AnnouncementRow> management(String enterprise, PagePosition before, int limit) {
        var query = Wrappers.<AnnouncementRow>lambdaQuery().isNull(AnnouncementRow::getDeletedAt)
            .orderByDesc(AnnouncementRow::getUpdatedAt, AnnouncementRow::getId);
        if (enterprise == null) {
            query.eq(AnnouncementRow::getScope, "platform").isNull(AnnouncementRow::getEnterpriseId);
        } else {
            query.eq(AnnouncementRow::getScope, "enterprise").eq(AnnouncementRow::getEnterpriseId, enterprise);
        }
        if (before != null) {
            query.and(next -> next.lt(AnnouncementRow::getUpdatedAt, before.time())
                .or(sameTime -> sameTime.eq(AnnouncementRow::getUpdatedAt, before.time())
                    .lt(AnnouncementRow::getId, before.id())));
        }
        return rows.selectPage(new Page<AnnouncementRow>(1, limit, false), query).getRecords();
    }

    private MPJLambdaWrapper<AnnouncementRow> selectContent(MPJLambdaWrapper<AnnouncementRow> query) {
        return query.selectAll(AnnouncementRow.class)
            .selectAs(AnnouncementReadRow::getReadAt, AnnouncementQueryRow::getReadAt);
    }

    private MPJLambdaWrapper<AnnouncementRow> visible(AuthContext actor, boolean unread) {
        var query = JoinWrappers.lambda(AnnouncementRow.class).leftJoin(AnnouncementReadRow.class,
                on -> on.eq(AnnouncementReadRow::getAnnouncementId, AnnouncementRow::getId)
                    .eq(AnnouncementReadRow::getContentVersion, AnnouncementRow::getContentVersion)
                    .eq(AnnouncementReadRow::getUserId, actor.userId())).eq(AnnouncementRow::getEnabled, true)
            .isNull(AnnouncementRow::getDeletedAt).and(scope -> scope.eq(AnnouncementRow::getScope, "platform")
                .or(enterprise -> enterprise.eq(AnnouncementRow::getScope, "enterprise")
                    .eq(AnnouncementRow::getEnterpriseId, actor.enterpriseId())));
        if (unread) {
            query.isNull(AnnouncementReadRow::getUserId);
        }
        return query;
    }
}
