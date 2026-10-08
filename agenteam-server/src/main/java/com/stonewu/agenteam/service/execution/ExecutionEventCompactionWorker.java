package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionEventSqlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;

/**
 * 按索引选择尚未转换的增量；每个会话一次最多处理 128 行，避免长时间占用消息保存。
 */
@Service
public class ExecutionEventCompactionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEventCompactionWorker.class);
    private final ExecutionEventSqlMapper statements;
    private final ExecutionEventCompactionService compaction;
    private final boolean enabled;

    public ExecutionEventCompactionWorker(ExecutionEventSqlMapper statements,
                                          ExecutionEventCompactionService compaction,
                                          @Value("${execution.events.history-compaction-enabled:true}") boolean enabled) {
        this.statements = statements;
        this.compaction = compaction;
        this.enabled = enabled;
    }

    public void compactPending() {
        if (!enabled) {
            return;
        }
        var visited = new HashSet<String>();
        for (var row : statements.legacyDeltas(32)) {
            if (!visited.add(row.getConversationId())) {
                continue;
            }
            try {
                var result = compaction.compact(row.getEnterpriseId(), row.getConversationId(), row.getRunId(),
                    row.getConversationSequence() - 1);
                if (result.convertedRows() > 0) {
                    LOG.info("历史消息增量已合并，会话编号 {}，转换 {} 行，减少 {} 行", row.getConversationId(),
                        result.convertedRows(), result.removedRows());
                }
            } catch (RuntimeException failed) {
                LOG.warn("历史消息增量合并失败，将保留原记录并稍后重试，会话编号 {}，执行编号 {}",
                    row.getConversationId(), row.getRunId(), failed);
            }
            if (visited.size() >= 4) {
                return;
            }
        }
    }
}
