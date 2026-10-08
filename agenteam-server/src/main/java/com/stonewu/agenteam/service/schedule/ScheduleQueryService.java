package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleViewMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduleOccurrenceRecord;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.model.schedule.response.ScheduleView;
import com.stonewu.agenteam.model.schedule.response.ScheduleActionView;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceDetail;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 私有计划和发生记录使用本人范围及带签名的分页位置。
 */
@Service
@Transactional(readOnly = true)
public class ScheduleQueryService {
    private final SchedulePolicy policy;
    private final ScheduleMapper schedules;
    private final ScheduleOccurrenceMapper occurrences;
    private final ScheduleViewMapper views;
    private final ListPagination pagination;
    private final ScheduleActionRegistry actions;
    private final ScheduleNotificationTargetService targets;
    private final ScheduleRecipientViewService recipients;
    private final ScheduleOccurrenceSqlMapper occurrenceRows;

    public ScheduleQueryService(SchedulePolicy policy, ScheduleMapper schedules, ScheduleOccurrenceMapper occurrences,
                                ScheduleViewMapper views, ListPagination pagination, ScheduleActionRegistry actions,
                                ScheduleNotificationTargetService targets, ScheduleRecipientViewService recipients, ScheduleOccurrenceSqlMapper occurrenceRows) {
        this.policy = policy;
        this.schedules = schedules;
        this.occurrences = occurrences;
        this.views = views;
        this.pagination = pagination;
        this.actions = actions;
        this.targets = targets;
        this.recipients = recipients;
        this.occurrenceRows = occurrenceRows;
    }

    public ScheduleView get(AuthContext actor, String id) {
        policy.view(actor);
        return view(policy.require(actor, id, false, false));
    }

    public PageResponse<ScheduleView> list(AuthContext actor, String query, String cursor, Integer count) {
        policy.view(actor);
        String search = pagination.query(query);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "schedules", search,
            "updated_desc");
        var rows = schedules.list(actor.enterpriseId(), actor.userId(), search, pagination.read(cursor, binding),
            limit);
        var page = pagination.page(rows, limit, binding, row -> new PagePosition(row.updatedAt(), row.id()));
        return new PageResponse<>(views(page.items()), page.nextCursor(), page.hasMore());
    }

    public PageResponse<ScheduleOccurrenceView> occurrences(AuthContext actor, String id, String cursor,
                                                            Integer count) {
        policy.view(actor);
        policy.require(actor, id, false, false);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(),
            "schedules/" + id + "/occurrences", "", "scheduled_desc");
        var rows = occurrences.list(actor.enterpriseId(), id, pagination.read(cursor, binding), limit);
        var page = pagination.page(rows, limit, binding, row -> new PagePosition(row.scheduledFor(), row.id()));
        return new PageResponse<>(page.items().stream().map(views::view).toList(), page.nextCursor(), page.hasMore());
    }

    public ScheduleView view(ScheduleRecord record) {
        return views(List.of(record)).getFirst();
    }

    public ScheduleOccurrenceDetail occurrence(AuthContext actor, String schedule, String id) {
        policy.view(actor);
        policy.require(actor, schedule, false, false);
        var view = occurrences.active(actor.enterpriseId(), schedule, id).orElseThrow(ResourceAuthorizationService::unavailable);
        var row = occurrenceRows.selectOne(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, actor.enterpriseId())
            .eq(ScheduledOccurrenceRow::getScheduleId, schedule).eq(ScheduledOccurrenceRow::getId, id));
        return new ScheduleOccurrenceDetail(views.view(view), actions.require(row.getActionType(), row.getActionSchemaVersion()).recipientResults(actor, row));
    }

    private List<ScheduleView> views(List<ScheduleRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        String enterprise = records.getFirst().enterpriseId();
        var ids = records.stream().map(ScheduleRecord::id).toList();
        var summaries = occurrences.summaries(enterprise, ids).stream().collect(Collectors.groupingBy(ScheduleOccurrenceRecord::scheduleId));
        var chosen = recipients.views(enterprise, targets.forSchedules(enterprise, ids));
        return records.stream().map(record -> {
            var history = summaries.getOrDefault(record.id(), List.of());
            var active = history.stream().filter(value -> value.id().equals(record.activeOccurrenceId())).findFirst().orElse(null);
            var latest = history.isEmpty() ? null : history.getFirst();
            var handler = actions.require(record.actionType(), record.actionSchemaVersion());
            var action = new ScheduleActionView(record.actionType(), handler.name(), record.actionSchemaVersion(), handler.publicConfiguration(record),
                chosen.getOrDefault(record.id(), List.of()));
            return views.view(record, active, latest, action);
        }).toList();
    }
}
