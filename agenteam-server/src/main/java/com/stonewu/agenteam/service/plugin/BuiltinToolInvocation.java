package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.plugin.entity.ToolQueryResult;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolQueryPolicy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 已发送写入先按原编号查询；只有明确未执行且支持去重时才可再次发送。
 */
@Service
public class BuiltinToolInvocation {
    private final ToolCallTransactions transactions;
    private final ToolCallMapper calls;
    private final ToolQueryPolicy queries;
    private final ResourceJson json;

    public BuiltinToolInvocation(ToolCallTransactions transactions, ToolCallMapper calls, ToolQueryPolicy queries,
                                 ResourceJson json) {
        this.transactions = transactions;
        this.calls = calls;
        this.queries = queries;
        this.json = json;
    }

    public JsonNode invoke(BuiltinPluginAdapter adapter, ExecutionToolBinding binding, ToolCallRecord call,
                           JobLease lease,
                           JsonNode arguments, Duration timeout, Duration executionRemaining, ToolCallControl control) {
        Long deadline = executionRemaining.isZero() ? null : System.nanoTime() + executionRemaining.toNanos();
        boolean queryFirst = !call.readOnly() && call.submittedAt() != null;
        while (true) {
            control.requireActive();
            if (queryFirst) {
                if (!queries.canQuery(binding)) {
                    throw ToolCallTransactions.unknown();
                }
                var current = calls.find(call.enterpriseId(), call.id(), false).orElseThrow();
                if (current.queryCount() >= 3) {
                    throw ToolCallTransactions.unknown();
                }
                ToolQueryResult result;
                try (var queryControl = control.track(
                    new ToolCallControl(() -> transactions.beforeQuery(lease, binding, call.id())))) {
                    result = adapter.query(call.toolName(), call.operationId(), remaining(deadline, timeout),
                        queryControl);
                }
                control.requireActive();
                if (result == null || result.status() == ToolQueryResult.Status.UNKNOWN) {
                    throw ToolCallTransactions.unknown();
                }
                if (result.status() == ToolQueryResult.Status.COMPLETED) {
                    return result.result();
                }
                if (current.attemptCount() >= 2 || !queries.canRepeat(binding)) {
                    return json.tree(
                        Map.of("isError", true, "content", "目标系统已确认操作未执行，本次任务不再发送该操作。"));
                }
                if (deadline != null && deadline - System.nanoTime() <= Duration.ofSeconds(2).toNanos()) {
                    return json.tree(
                        Map.of("isError", true, "content", "目标系统已确认操作未执行，剩余时间不足，本次任务不再发送。"));
                }
                control.pause(Duration.ofSeconds(2));
            }
            try {
                return adapter.call(call.toolName(), call.operationId(), arguments.deepCopy(),
                    remaining(deadline, timeout), control);
            } catch (RuntimeException failure) {
                control.requireActive();
                var current = calls.find(call.enterpriseId(), call.id(), false).orElseThrow();
                if (call.readOnly() || current.submittedAt() == null || !queries.canQuery(binding)) {
                    throw failure;
                }
                queryFirst = true;
            }
        }
    }

    private Duration remaining(Long deadline, Duration limit) {
        if (deadline == null) {
            return limit;
        }
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw ToolCallTransactions.unknown();
        }
        var remaining = Duration.ofNanos(nanos);
        return remaining.compareTo(limit) < 0 ? remaining : limit;
    }
}
