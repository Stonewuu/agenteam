package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionEventSqlMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionEventStorageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史增量在会话锁内有界转换，删除原行与保存合并行必须一起提交。
 */
@Service
public class ExecutionEventCompactionService {
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final ExecutionEventSqlMapper statements;
    private final ExecutionEventStorageMapper storage;

    public ExecutionEventCompactionService(ConversationMapper conversations, RunMapper runs,
                                           ExecutionEventSqlMapper statements, ExecutionEventStorageMapper storage) {
        this.conversations = conversations;
        this.runs = runs;
        this.statements = statements;
        this.storage = storage;
    }

    public record Result(int convertedRows, int removedRows) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result compact(String enterprise, String conversation, String runId, long after) {
        var run = runs.find(enterprise, runId, false).orElse(null);
        if (run == null || !conversation.equals(run.conversationId())
            || conversations.find(enterprise, run.userId(), conversation, true).isEmpty()) {
            return new Result(0, 0);
        }
        var rows = statements.afterAgentEvent(enterprise, conversation, after, 128);
        var pendingRows = new ArrayList<AgentEventRow>();
        var pendingEvents = new ArrayList<ExecutionEvent>();
        int converted = 0, removed = 0;
        for (var row : rows) {
            var values = storage.decode(row);
            if (!pendingEvents.isEmpty() && !canJoin(pendingEvents, values)) {
                var result = save(pendingRows, pendingEvents);
                converted += result.convertedRows();
                removed += result.removedRows();
                pendingRows.clear();
                pendingEvents.clear();
            }
            if (!storage.compactable(values.getFirst())) {
                continue;
            }
            pendingRows.add(row);
            pendingEvents.addAll(values);
        }
        var result = save(pendingRows, pendingEvents);
        return new Result(converted + result.convertedRows(), removed + result.removedRows());
    }

    private boolean canJoin(List<ExecutionEvent> pending, List<ExecutionEvent> next) {
        var combined = new ArrayList<>(pending);
        for (var event : next) {
            if (!storage.canAppend(combined, event)) {
                return false;
            }
            combined.add(event);
        }
        return true;
    }

    private Result save(List<AgentEventRow> rows, List<ExecutionEvent> values) {
        if (rows.isEmpty() || rows.size() == 1 && rows.getFirst().getStorageVersion() != 1) {
            return new Result(0, 0);
        }
        var first = rows.getFirst();
        var removedIds = rows.stream().skip(1).map(AgentEventRow::getEventId).toList();
        if (statements.deleteMergedRows(first.getEnterpriseId(), first.getConversationId(),
            removedIds) != removedIds.size()) {
            throw new IllegalStateException("历史事件在合并前已经变化，当前事务必须回滚");
        }
        storage.replace(first, values, rows.getLast().getSequenceNo());
        return new Result((int) rows.stream().filter(row -> row.getStorageVersion() == 1).count(), removedIds.size());
    }
}
