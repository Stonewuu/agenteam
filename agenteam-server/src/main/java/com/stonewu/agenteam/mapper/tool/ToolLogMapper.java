package com.stonewu.agenteam.mapper.tool;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.response.ActorView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.entity.QueryTimeRange;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import com.stonewu.agenteam.model.tool.entity.ToolLogQueryRow;
import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import com.stonewu.agenteam.model.tool.response.ToolCallView;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 在分页前限制当前日志范围；列表和详情都不查询加密字段。
 */
@Repository
public class ToolLogMapper {
    private final ToolLogSqlMapper statements;
    private final ResourceJson json;

    public record Entry(ToolCallView call, String conversationId) {
    }

    public record Details(Entry entry, Map<String, Object> request, Map<String, Object> result) {
    }

    public ToolLogMapper(ToolLogSqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public List<Entry> list(AuthContext actor, List<DataScope> scopes, DataScope details, ToolLogQuery filter,
                            QueryTimeRange range, PagePosition cursor, int limit) {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("调用日志读取必须指定当前权限范围");
        }
        var ranges = scopes.stream().map(
                scope -> new OwnerQueryScope(actor.enterpriseId(), actor.userId(), scope == null ? "own" : scope.code()))
            .toList();
        var detailRange = details == null ? null : new OwnerQueryScope(actor.enterpriseId(), actor.userId(),
            details.code());
        return statements.listCalls(actor.enterpriseId(), ranges, detailRange, filter, range, cursor, limit + 1)
            .stream().map(this::map).toList();
    }

    public Optional<Details> details(AuthContext actor, DataScope view, DataScope details, String id) {
        var viewRange = new OwnerQueryScope(actor.enterpriseId(), actor.userId(), view == null ? "own" : view.code());
        var detailRange = new OwnerQueryScope(actor.enterpriseId(), actor.userId(),
            details == null ? "own" : details.code());
        return statements.callDetails(actor.enterpriseId(), viewRange, detailRange, id).stream().map(row ->
                new Details(map(row), json.object(json.read(row.getRequestRedactedJson())),
                    row.getResultRedactedJson() == null ? null : json.object(json.read(row.getResultRedactedJson()))))
            .findFirst();
    }


    private Entry map(ToolLogQueryRow row) {
        return new Entry(
            new ToolCallView(row.getId(), row.getRunId(), new ActorView(row.getActorUserId(), row.getDisplayName()),
                row.getToolName(), row.getStatus(), row.getOperationClass(), row.getDurationMs(),
                row.getErrorSummary(), row.getCreatedAt().toInstant().toString(), row.getCanViewDetails(),
                row.getRunSource(), null), row.getConversationId());
    }
}
