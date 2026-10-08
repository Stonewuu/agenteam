package com.stonewu.agenteam.mapper.export;

import com.stonewu.agenteam.mapper.execution.ExecutionJson;
import com.stonewu.agenteam.mapper.query.MappedCallbackFailures;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/**
 * 只读取本人会话的公开消息和内容块，不查询模型指令、上下文或框架检查点。
 */
@Repository
public class ConversationExportMapper {
    private final ConversationExportSqlMapper statements;
    private final ExecutionJson json;

    public ConversationExportMapper(ConversationExportSqlMapper statements, ExecutionJson json) {
        this.statements = statements;
        this.json = json;
    }

    public void append(String enterprise, String user, String conversation, CsvExportMapper.Rows output) {
        Instant[] first = new Instant[1];
        try {
            statements.readMessages(enterprise, user, conversation, CsvExportMapper.MAX_ROWS + 1, context -> {
                if (context.getResultCount() > CsvExportMapper.MAX_ROWS) {
                    throw CsvExportMapper.limit("导出超过十万条消息，请缩小范围。");
                }
                var row = context.getResultObject();
                Instant when = row.getCreatedAt();
                if (first[0] == null) {
                    first[0] = when;
                }
                if (Duration.between(first[0], when).compareTo(Duration.ofDays(90)) > 0) {
                    throw CsvExportMapper.limit("会话时间跨度超过九十天，无法一次导出。");
                }
                var blocks = json.blocks(row.getBlocksJson()).stream()
                    .sorted(Comparator.comparingInt(ContentBlock::displayOrder)).toList();
                String id = row.getId(), role = row.getRole(), attempt = row.getAttemptNo(), status = row.getStatus();
                if (blocks.isEmpty()) {
                    output.row(user, id, when.toString(), role, attempt, status, null, null, "text", null,
                        row.getContent(), null, null);
                }
                for (var block : blocks) {
                    output.row(user, id, when.toString(), role, attempt, status, block.id(), block.parentBlockId(),
                        block.type(), block.label(), block.text(),
                        block.tool() == null ? null : block.tool().input(),
                        block.tool() == null ? null : block.tool().result());
                }
            });
        } catch (RuntimeException failure) {
            throw MappedCallbackFailures.propagate(failure);
        }
    }
}
