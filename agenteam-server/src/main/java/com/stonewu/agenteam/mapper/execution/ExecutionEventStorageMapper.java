package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 调用方先锁定会话并开启事务；合并保存不能改变已经分发的原事件。
 */
@Repository
public class ExecutionEventStorageMapper {
    private final ExecutionEventSqlMapper statements;
    private final ExecutionEventCodec events;
    private final ExecutionDeltaBatchCodec batches;

    public ExecutionEventStorageMapper(ExecutionEventSqlMapper statements, ExecutionEventCodec events,
                                       ExecutionDeltaBatchCodec batches) {
        this.statements = statements;
        this.events = events;
        this.batches = batches;
    }

    public void append(ExecutionEvent event, long runSequence, Instant now) {
        if (batches.compactable(event)) {
            var previous = statements.lastForConversation(event.enterpriseId(), event.conversationId());
            if (previous != null && "message.delta".equals(previous.getEventType())) {
                var values = new ArrayList<>(decode(previous));
                if (batches.canAppend(values, event)) {
                    values.add(event);
                    replace(previous, values, runSequence);
                    return;
                }
            }
        }
        var row = row(List.of(event), runSequence);
        row.setStartedAt(now);
        row.setCreatedAt(now);
        if (statements.insert(row) != 1) {
            throw new IllegalStateException("事件没有完整保存");
        }
    }

    public List<ExecutionEvent> decode(AgentEventRow row) {
        var values = batches.decode(row.getStorageVersion(), row.getPayloadJson(), row.getPayloadHash());
        var first = values.getFirst();
        var last = values.getLast();
        if (!row.getEventId().equals(first.eventId()) || !row.getEnterpriseId().equals(first.enterpriseId())
            || !row.getConversationId().equals(first.conversationId()) || !row.getRunId().equals(first.runId())
            || !row.getEventType().equals(first.type())
            || row.getConversationSequence() != Long.parseLong(last.sequence())) {
            throw new IllegalStateException("数据库事件的对象或顺序编号与正文不一致");
        }
        return values;
    }

    public void replace(AgentEventRow previous, List<ExecutionEvent> values, long lastRunSequence) {
        if (statements.replaceBatch(previous, row(values, lastRunSequence)) != 1) {
            throw new IllegalStateException("合并事件保存前已经变化，当前事务必须回滚");
        }
    }

    public boolean canAppend(List<ExecutionEvent> values, ExecutionEvent next) {
        return batches.canAppend(values, next);
    }

    public boolean compactable(ExecutionEvent event) {
        return batches.compactable(event);
    }

    private AgentEventRow row(List<ExecutionEvent> values, long lastRunSequence) {
        var first = values.getFirst();
        var last = values.getLast();
        boolean compact = batches.compactable(first);
        var encoded = compact ? batches.encode(values) : events.encode(first);
        var row = new AgentEventRow();
        row.setEventId(first.eventId());
        row.setEnterpriseId(first.enterpriseId());
        row.setConversationId(first.conversationId());
        row.setRunId(first.runId());
        row.setSequenceNo(lastRunSequence);
        row.setConversationSequence(Long.parseLong(last.sequence()));
        row.setProtocolVersion(first.protocolVersion());
        row.setStorageVersion(compact ? ExecutionDeltaBatchCodec.STORAGE_VERSION : 1);
        row.setEventType(first.type());
        row.setPayloadJson(encoded.data());
        row.setPayloadHash(encoded.hash());
        row.setFinishedAt(Instant.parse(last.createdAt()));
        return row;
    }
}
