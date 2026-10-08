package com.stonewu.agenteam.mapper.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentSqlMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.operations.entity.OperationalSnapshot;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 运维汇总只读取状态索引，不读取后台任务条件、调用参数和消息正文。
 */
@Repository
public class OperationalStateMapper {
    public static final List<String> JOB_KINDS = List.of("run", "document_parse", "file_scan", "mail", "notification",
        "event_publish", "export", "cleanup");
    public static final List<String> ACTIVE_RUN_STATES = List.of("queued", "running", "waiting_approval", "cancelling");
    private final OperationalStateSqlMapper statements;
    private final FileSqlMapper files;
    private final KnowledgeDocumentSqlMapper documents;
    private final ToolCallSqlMapper calls;

    public OperationalStateMapper(OperationalStateSqlMapper statements, FileSqlMapper files,
                                  KnowledgeDocumentSqlMapper documents, ToolCallSqlMapper calls) {
        this.statements = statements;
        this.files = files;
        this.documents = documents;
        this.calls = calls;
    }

    public OperationalSnapshot read(Instant now) {
        var values = new LinkedHashMap<String, Double>();
        for (String kind : JOB_KINDS) {
            for (String metric : List.of("queued", "ready", "leased", "expired", "oldest_seconds")) {
                values.put("job." + kind + "." + metric, 0.0);
            }
        }
        for (var row : statements.readBackgroundJob(Timestamp.from(now))) {
            String prefix = "job." + row.getKind() + ".";
            values.put(prefix + "queued", row.getQueued());
            values.put(prefix + "ready", row.getReady());
            values.put(prefix + "leased", row.getLeased());
            values.put(prefix + "expired", row.getExpired());
            values.put(prefix + "oldest_seconds", age(now, row.getOldest()));
        }
        for (String status : ACTIVE_RUN_STATES) {
            values.put("run." + status, 0.0);
        }
        for (var row : statements.readAgentRun()) {
            values.put("run." + row.getStatus(), row.getTotal());
        }
        var events = DataAccessUtils.nullableSingleResult(statements.readAgentEvent());
        values.put("event.pending", events.getTotal());
        values.put("event.oldest_seconds", age(now, events.getOldest()));
        values.put("file.rejected", files.selectCount(new LambdaQueryWrapper<FileObjectRow>()
            .eq(FileObjectRow::getStatus, "rejected").isNull(FileObjectRow::getDeletedAt)).doubleValue());
        values.put("knowledge.failed", documents.selectCount(new LambdaQueryWrapper<KnowledgeDocumentRow>()
            .eq(KnowledgeDocumentRow::getStatus, "failed").isNull(KnowledgeDocumentRow::getDeletedAt)).doubleValue());
        values.put("tool.unknown", calls.selectCount(new LambdaQueryWrapper<ToolCallRow>()
            .eq(ToolCallRow::getStatus, "unknown")).doubleValue());
        return new OperationalSnapshot(now, values);
    }

    private static double age(Instant now, Timestamp oldest) {
        return oldest == null ? 0 : Math.max(0, (now.toEpochMilli() - oldest.toInstant().toEpochMilli()) / 1000.0);
    }
}
