package com.stonewu.agenteam.service.modelusage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageCostItemMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageQueryMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageCostItemRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageDay;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageFilter;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageOptionRow;
import com.stonewu.agenteam.model.modelusage.response.ModelUsageOptionView;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.modelusage.request.ModelUsageQueryRequest;
import com.stonewu.agenteam.model.modelusage.response.ConversationModelUsageView;
import com.stonewu.agenteam.model.modelusage.response.ModelCallView;
import com.stonewu.agenteam.model.modelusage.response.ModelUsageSummaryView;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 企业查询需要用量权限；本人对话只查询对应所有者，不把配额记录当成模型用量。
 */
@Service
public class ModelUsageQueryService {
    private final ModelUsageRecordMapper rows;
    private final ModelUsageCostItemMapper items;
    private final ModelUsageQueryMapper aggregate;
    private final ModelUsageViewMapper views;
    private final EnterpriseAuthorizationService authorization;
    private final ConversationQueryService conversations;
    private final ListPagination pagination;
    private final Clock clock;

    public ModelUsageQueryService(ModelUsageRecordMapper rows, ModelUsageCostItemMapper items, ModelUsageQueryMapper aggregate,
                                   ModelUsageViewMapper views, EnterpriseAuthorizationService authorization,
                                   ConversationQueryService conversations, ListPagination pagination, Clock clock) {
        this.rows = rows;
        this.items = items;
        this.aggregate = aggregate;
        this.views = views;
        this.authorization = authorization;
        this.conversations = conversations;
        this.pagination = pagination;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ModelUsageSummaryView summary(AuthContext actor, ModelUsageQueryRequest request) {
        var filter = enterpriseFilter(actor, request);
        boolean daily = "day".equals(request.groupBy());
        if (request.groupBy() != null && !daily) {
            throw ApiException.invalidField("groupBy", "只支持按日分组。");
        }
        return views.summary(filter, aggregate.aggregate(filter, daily ? days(filter) : List.of()), daily);
    }

    @Transactional(readOnly = true)
    public List<ModelUsageOptionView> options(AuthContext actor, ModelUsageQueryRequest request, String kind, String search) {
        var filter = enterpriseFilter(actor, request);
        String text = pagination.query(search);
        var query = JoinWrappers.lambda(ModelUsageRecordRow.class).eq(ModelUsageRecordRow::getEnterpriseId, filter.enterpriseId())
            .ge(ModelUsageRecordRow::getDispatchedAt, filter.from()).lt(ModelUsageRecordRow::getDispatchedAt, filter.to());
        if (kind.equals("model")) {
            query.selectAs(ModelUsageRecordRow::getModelProfileId, ModelUsageOptionRow::getId)
                .selectAs(ModelProfileRow::getName, ModelUsageOptionRow::getName)
                .innerJoin(ModelProfileRow.class, on -> on.eq(ModelProfileRow::getEnterpriseId, ModelUsageRecordRow::getEnterpriseId)
                    .eq(ModelProfileRow::getId, ModelUsageRecordRow::getModelProfileId))
                .like(!text.isEmpty(), ModelProfileRow::getName, text)
                .groupBy(ModelUsageRecordRow::getModelProfileId).groupBy(ModelProfileRow::getName).orderByAsc(ModelProfileRow::getName);
        } else if (kind.equals("user")) {
            query.selectAs(ModelUsageRecordRow::getUserId, ModelUsageOptionRow::getId)
                .selectAs(AppUserRow::getDisplayName, ModelUsageOptionRow::getName)
                .leftJoin(AppUserRow.class, AppUserRow::getId, ModelUsageRecordRow::getUserId)
                .like(!text.isEmpty(), AppUserRow::getDisplayName, text)
                .groupBy(ModelUsageRecordRow::getUserId).groupBy(AppUserRow::getDisplayName).orderByAsc(AppUserRow::getDisplayName);
        } else {
            throw ApiException.invalidField("kind", "请选择模型或用户筛选。");
        }
        return rows.selectJoinPage(new Page<ModelUsageOptionRow>(1, 100, false), ModelUsageOptionRow.class, query).getRecords()
            .stream().map(row -> new ModelUsageOptionView(row.getId(), row.getName() == null ? "已移除用户" : row.getName())).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ModelCallView> calls(AuthContext actor, ModelUsageQueryRequest request) {
        return page(actor, enterpriseFilter(actor, request), request.cursor(), request.limit());
    }

    @Transactional(readOnly = true)
    public ModelCallView detail(AuthContext actor, String id) {
        authorization.requireEnterpriseScope(authorization.require(actor, "usage.view"));
        var row = rows.selectOne(Wrappers.<ModelUsageRecordRow>lambdaQuery().eq(ModelUsageRecordRow::getEnterpriseId, actor.enterpriseId())
            .eq(ModelUsageRecordRow::getId, identifier(id, "callId")));
        if (row == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return views.call(row, costs(actor.enterpriseId(), List.of(row)).getOrDefault(row.getId(), List.of()));
    }

    @Transactional(readOnly = true)
    public ConversationModelUsageView conversation(AuthContext actor, String conversation, String runId, String cursor, Integer limit) {
        conversations.readable(actor, conversation);
        var filter = new ModelUsageFilter(actor.enterpriseId(), null, null, "UTC", null, actor.userId(), conversation, identifier(runId, "runId"));
        return new ConversationModelUsageView(views.summary(filter, aggregate.aggregate(filter, List.of()), false), page(actor, filter, cursor, limit));
    }

    private ModelUsageFilter enterpriseFilter(AuthContext actor, ModelUsageQueryRequest request) {
        authorization.requireEnterpriseScope(authorization.require(actor, "usage.view"));
        String timezone = request.timezone() == null ? "UTC" : request.timezone();
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw ApiException.invalidField("timezone", "请选择有效时区。");
        }
        var zone = ZoneId.of(timezone);
        var month = clock.instant().atZone(zone).toLocalDate().withDayOfMonth(1);
        Instant from = request.from() == null ? month.atStartOfDay(zone).toInstant() : request.from();
        Instant to = request.to() == null ? month.plusMonths(1).atStartOfDay(zone).toInstant() : request.to();
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(93)) > 0) {
            throw ApiException.invalidField("to", "结束时间须晚于开始时间，单次范围最多 93 天。");
        }
        return new ModelUsageFilter(actor.enterpriseId(), from, to, timezone, identifier(request.modelProfileId(), "modelProfileId"),
            identifier(request.userId(), "userId"), null, null);
    }

    private PageResponse<ModelCallView> page(AuthContext actor, ModelUsageFilter filter, String cursor, Integer limit) {
        int size = pagination.limit(limit);
        String scope = filter.from() + "|" + filter.to() + "|" + filter.modelProfileId() + "|" + filter.userId()
            + "|" + filter.conversationId() + "|" + filter.runId();
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "model-calls", scope, "dispatched_desc");
        var position = pagination.read(cursor, binding);
        var query = criteria(filter).orderByDesc(ModelUsageRecordRow::getDispatchedAt, ModelUsageRecordRow::getId);
        if (position != null) {
            query.and(part -> part.lt(ModelUsageRecordRow::getDispatchedAt, position.time()).or()
                .nested(equal -> equal.eq(ModelUsageRecordRow::getDispatchedAt, position.time()).lt(ModelUsageRecordRow::getId, position.id())));
        }
        var selected = rows.selectPage(new Page<ModelUsageRecordRow>(1, size + 1, false), query).getRecords();
        var page = pagination.page(selected, size, binding, row -> new PagePosition(row.getDispatchedAt(), row.getId(), null));
        var costs = costs(actor.enterpriseId(), page.items());
        return new PageResponse<>(page.items().stream().map(row -> views.call(row, costs.getOrDefault(row.getId(), List.of()))).toList(),
            page.nextCursor(), page.hasMore());
    }

    private LambdaQueryWrapper<ModelUsageRecordRow> criteria(ModelUsageFilter filter) {
        return Wrappers.<ModelUsageRecordRow>lambdaQuery().eq(ModelUsageRecordRow::getEnterpriseId, filter.enterpriseId())
            .ge(filter.from() != null, ModelUsageRecordRow::getDispatchedAt, filter.from()).lt(filter.to() != null, ModelUsageRecordRow::getDispatchedAt, filter.to())
            .eq(filter.modelProfileId() != null, ModelUsageRecordRow::getModelProfileId, filter.modelProfileId())
            .eq(filter.userId() != null, ModelUsageRecordRow::getUserId, filter.userId())
            .eq(filter.conversationId() != null, ModelUsageRecordRow::getConversationId, filter.conversationId())
            .eq(filter.runId() != null, ModelUsageRecordRow::getRunId, filter.runId());
    }

    private Map<String, List<ModelUsageCostItemRow>> costs(String enterprise, List<ModelUsageRecordRow> values) {
        var ids = values.stream().filter(row -> row.getPriceStatus().equals("priced") || row.getPriceStatus().equals("partial"))
            .map(ModelUsageRecordRow::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return items.selectList(Wrappers.<ModelUsageCostItemRow>lambdaQuery().eq(ModelUsageCostItemRow::getEnterpriseId, enterprise)
            .in(ModelUsageCostItemRow::getUsageRecordId, ids).orderByAsc(ModelUsageCostItemRow::getMeter, ModelUsageCostItemRow::getCacheTtlSeconds))
            .stream().collect(Collectors.groupingBy(ModelUsageCostItemRow::getUsageRecordId));
    }

    public static List<ModelUsageDay> days(ModelUsageFilter filter) {
        var zone = ZoneId.of(filter.timezone());
        var result = new ArrayList<ModelUsageDay>();
        for (var date = filter.from().atZone(zone).toLocalDate(); date.atStartOfDay(zone).toInstant().isBefore(filter.to()); date = date.plusDays(1)) {
            result.add(new ModelUsageDay(date.toString(), date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        return List.copyOf(result);
    }

    private String identifier(String value, String field) {
        if (value != null && (value.isBlank() || value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl))) {
            throw ApiException.invalidField(field, "请选择有效记录。");
        }
        return value;
    }
}
