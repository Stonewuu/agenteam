package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolLogMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadPreview;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import com.stonewu.agenteam.model.tool.response.ToolCallDetailsView;
import com.stonewu.agenteam.model.tool.response.ToolCallView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.http.QueryTimeRangeService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日志详情需要额外权限，审计失败时不能返回正文。
 */
@Service
public class ToolLogService {
    private final ToolLogMapper logs;
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final ListPagination pagination;
    private final AuditEventService audit;
    private final QueryTimeRangeService ranges;
    private final ConversationQueryService conversations;
    private final ResourceJson json;

    public ToolLogService(ToolLogMapper logs, EnterpriseAuthorizationService authorization,
                          PermissionMapper permissions, ListPagination pagination, AuditEventService audit,
                          QueryTimeRangeService ranges, ConversationQueryService conversations, ResourceJson json) {
        this.logs = logs;
        this.authorization = authorization;
        this.permissions = permissions;
        this.pagination = pagination;
        this.audit = audit;
        this.ranges = ranges;
        this.conversations = conversations;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ToolCallView> list(AuthContext actor, ToolLogQuery query, String cursor, Integer limit) {
        var scope = authorization.require(actor, "tool_log.view");
        var details = permissions.operationScope(actor.userId(), actor.enterpriseId(), "tool_log.details").orElse(null);
        var filter = validate(query);
        int size = pagination.limit(limit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "tool-calls",
            json.write(json.tree(filter)) + "|" + scope.code(), "created_desc");
        var position = pagination.read(cursor, binding);
        var range = ranges.resolve(filter.from(), filter.to(), position);
        var rows = logs.list(actor, List.of(scope), details, filter, range, position, size);
        var page = pagination.page(rows, size, binding,
            value -> new PagePosition(Instant.parse(value.call().createdAt()), value.call().id(), range.cursorValue()));
        Map<String, Boolean> readable = new HashMap<>();
        return new PageResponse<>(page.items().stream().map(value -> view(actor, value, readable)).toList(),
            page.nextCursor(), page.hasMore());
    }

    @Transactional
    public ToolCallDetailsView details(AuthContext actor, String id) {
        var view = authorization.lockAndRequire(actor, "tool_log.view");
        var details = authorization.require(actor, "tool_log.details");
        var result = logs.details(actor, view, details, id).orElseThrow(ResourceAuthorizationService::unavailable);
        audit.record(actor.enterpriseId(), actor.user(), "tool_log.details", "tool_call", result.entry().call().id(),
            "查看脱敏工具调用详情", Map.of());
        return new ToolCallDetailsView(view(actor, result.entry(), new HashMap<>()), preview(result.request()),
            preview(result.result()));
    }

    private Map<String, Object> preview(Map<String, Object> value) {
        return value == null ? null : json.object(json.read(ToolPayloadPreview.value(json.tree(value))));
    }

    public ToolLogQuery validate(ToolLogQuery query) {
        String owner = query.actorUserId() == null || query.actorUserId().isBlank() ? null : query.actorUserId().trim();
        if (owner != null && (owner.length() > 100 || owner.codePoints().anyMatch(Character::isISOControl))) {
            throw ApiException.invalidField("actorUserId", "请选择有效的发起成员。");
        }
        String status = option(query.status(), "status",
            Set.of("prepared", "waiting_approval", "running", "succeeded", "failed", "cancelled", "unknown"));
        String source = option(query.source(), "source",
            Set.of("interactive", "preview", "scheduled", "manual_schedule"));
        return new ToolLogQuery(ranges.normalize(query.from(), "from"), ranges.normalize(query.to(), "to"), owner,
            status, source, pagination.query(query.query()));
    }

    private String option(String value, String field, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw ApiException.invalidField(field, "请选择有效的筛选条件。");
        }
        return value;
    }

    private ToolCallView view(AuthContext actor, ToolLogMapper.Entry value, Map<String, Boolean> checked) {
        String id = value.conversationId();
        if (id == null || !value.call().actor().id().equals(actor.userId())) {
            return value.call();
        }
        return checked.computeIfAbsent(id, candidate -> conversations.canRead(actor, candidate)) ? value.call()
            .withConversation(id) : value.call();
    }
}
