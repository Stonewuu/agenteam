package com.stonewu.agenteam.mapper.file;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.file.entity.MessageAttachmentRow;
import com.stonewu.agenteam.model.file.entity.MessageAttachmentRowView;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/**
 * 消息附件关联真实消息，文件列表与读取资格使用类型化关联查询。
 */
@Repository
public class MessageAttachmentMapper {
    private final MessageAttachmentSqlMapper statements;

    public MessageAttachmentMapper(MessageAttachmentSqlMapper statements) {
        this.statements = statements;
    }

    public void bind(String enterprise, String message, List<String> files, Instant now) {
        if (!files.isEmpty()) {
            var rows = IntStream.range(0, files.size()).mapToObj(index -> {
                var row = new MessageAttachmentRow();
                row.setEnterpriseId(enterprise);
                row.setMessageId(message);
                row.setFileId(files.get(index));
                row.setOrdinal(index);
                row.setCreatedAt(now);
                return row;
            }).toList();
            statements.insert(rows, 100);
        }
    }

    public boolean readable(AuthContext actor, FileRecord file, Instant now) {
        return statements.selectJoinCount(JoinWrappers.lambda(MessageAttachmentRow.class)
            .innerJoin(AgentMessageRow.class,
                on -> on.eq(AgentMessageRow::getEnterpriseId, MessageAttachmentRow::getEnterpriseId)
                    .eq(AgentMessageRow::getId, MessageAttachmentRow::getMessageId))
            .innerJoin(AgentConversationRow.class,
                on -> on.eq(AgentConversationRow::getEnterpriseId, AgentMessageRow::getEnterpriseId)
                    .eq(AgentConversationRow::getId, AgentMessageRow::getConversationId))
            .eq(MessageAttachmentRow::getEnterpriseId, actor.enterpriseId())
            .eq(MessageAttachmentRow::getFileId, file.id())
            .eq(AgentConversationRow::getUserId, actor.userId()).ne(AgentConversationRow::getStatus, "deleted")
            .and(part -> part.ne(AgentConversationRow::getMode, "preview")
                .or(recent -> recent.gt(AgentConversationRow::getCreatedAt, now.minusSeconds(7 * 86400))))) > 0;
    }

    public Map<String, List<Map<String, Object>>> forMessages(String enterprise, List<String> ids) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        var rows = statements.selectJoinList(MessageAttachmentRowView.class,
            JoinWrappers.lambda(MessageAttachmentRow.class)
                .select(MessageAttachmentRow::getMessageId)
                .select(FileObjectRow::getId, FileObjectRow::getOriginalName, FileObjectRow::getMediaType,
                    FileObjectRow::getSizeBytes, FileObjectRow::getStatus, FileObjectRow::getErrorCode,
                    FileObjectRow::getCreatedAt)
                .innerJoin(FileObjectRow.class,
                    on -> on.eq(FileObjectRow::getEnterpriseId, MessageAttachmentRow::getEnterpriseId)
                        .eq(FileObjectRow::getId, MessageAttachmentRow::getFileId))
                .eq(MessageAttachmentRow::getEnterpriseId, enterprise).in(MessageAttachmentRow::getMessageId, ids)
                .orderByAsc(MessageAttachmentRow::getMessageId, MessageAttachmentRow::getOrdinal));
        for (var row : rows) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", row.getId());
            value.put("name", row.getOriginalName());
            value.put("mediaType", row.getMediaType());
            value.put("sizeBytes", row.getSizeBytes());
            value.put("status", row.getStatus());
            value.put("errorCode", row.getErrorCode());
            value.put("createdAt", row.getCreatedAt().toString());
            result.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>())
                .add(Collections.unmodifiableMap(value));
        }
        return result;
    }

    public List<String> forConversation(String enterprise, String user, String conversation, int limit) {
        var query = JoinWrappers.lambda(MessageAttachmentRow.class).select(MessageAttachmentRow::getFileId).distinct()
            .innerJoin(AgentMessageRow.class,
                on -> on.eq(AgentMessageRow::getEnterpriseId, MessageAttachmentRow::getEnterpriseId)
                    .eq(AgentMessageRow::getId, MessageAttachmentRow::getMessageId))
            .innerJoin(AgentConversationRow.class,
                on -> on.eq(AgentConversationRow::getEnterpriseId, AgentMessageRow::getEnterpriseId)
                    .eq(AgentConversationRow::getId, AgentMessageRow::getConversationId))
            .eq(MessageAttachmentRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, conversation)
            .eq(AgentConversationRow::getUserId, user)
            .ne(AgentConversationRow::getStatus, "deleted").eq(AgentMessageRow::getRole, "user")
            .orderByAsc(MessageAttachmentRow::getFileId);
        return statements.selectJoinPage(new Page<>(1, limit, false), MessageAttachmentRow.class, query)
            .getRecords().stream().map(MessageAttachmentRow::getFileId).toList();
    }

    public void removeConversation(String enterprise, String conversation, Instant now) {
        var ids = statements.selectJoinList(MessageAttachmentRow.class, JoinWrappers.lambda(MessageAttachmentRow.class)
                .select(MessageAttachmentRow::getFileId).distinct()
                .innerJoin(AgentMessageRow.class,
                    on -> on.eq(AgentMessageRow::getEnterpriseId, MessageAttachmentRow::getEnterpriseId)
                        .eq(AgentMessageRow::getId, MessageAttachmentRow::getMessageId))
                .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentMessageRow::getConversationId, conversation))
            .stream().map(MessageAttachmentRow::getFileId).toList();
        statements.removeConversationAttachments(enterprise, conversation);
        if (!ids.isEmpty()) {
            statements.expireDetachedFiles(enterprise, ids, now);
        }
    }
}
