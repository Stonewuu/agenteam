package com.stonewu.agenteam.mapper.file;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.MessageAttachmentRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 汇总整段会话的附件关系与产出文件，不依赖页面已加载的消息。
 */
@Repository
public class ConversationFileMapper {
    private final FileSqlMapper files;

    public ConversationFileMapper(FileSqlMapper files) {
        this.files = files;
    }

    public List<FileObjectRow> list(String enterprise, String user, String conversation, PagePosition after, int limit,
                                    Instant now) {
        var query = query(enterprise, user, conversation, now).selectAll(FileObjectRow.class).distinct();
        if (after != null) {
            query.and(part -> part.lt(FileObjectRow::getCreatedAt, after.time())
                .or(equal -> {
                    equal.eq(FileObjectRow::getCreatedAt, after.time());
                    // 同一时间按统一编号排序，工作文件排在附件之前。
                    if (after.id().startsWith("f.")) {
                        equal.lt(FileObjectRow::getId, after.id().substring(2));
                    }
                }));
        }
        query.orderByDesc(FileObjectRow::getCreatedAt, FileObjectRow::getId);
        return files.selectJoinPage(new Page<>(1, limit, false), FileObjectRow.class, query).getRecords();
    }

    public boolean contains(String enterprise, String user, String conversation, String file, Instant now) {
        return files.selectJoinCount(query(enterprise, user, conversation, now).eq(FileObjectRow::getId, file)) > 0;
    }

    private MPJLambdaWrapper<FileObjectRow> query(String enterprise, String user, String conversation, Instant now) {
        return JoinWrappers.lambda(FileObjectRow.class)
            .leftJoin(AgentRunRow.class, on -> on.eq(AgentRunRow::getEnterpriseId, FileObjectRow::getEnterpriseId)
                .eq(AgentRunRow::getId, FileObjectRow::getRunId))
            .leftJoin(MessageAttachmentRow.class,
                on -> on.eq(MessageAttachmentRow::getEnterpriseId, FileObjectRow::getEnterpriseId)
                    .eq(MessageAttachmentRow::getFileId, FileObjectRow::getId))
            .leftJoin(AgentMessageRow.class,
                on -> on.eq(AgentMessageRow::getEnterpriseId, MessageAttachmentRow::getEnterpriseId)
                    .eq(AgentMessageRow::getId, MessageAttachmentRow::getMessageId))
            .eq(FileObjectRow::getEnterpriseId, enterprise).eq(FileObjectRow::getOwnerUserId, user)
            .eq(FileObjectRow::getStatus, "ready").isNull(FileObjectRow::getDeletedAt)
            .and(part -> part.isNull(FileObjectRow::getExpiresAt).or().gt(FileObjectRow::getExpiresAt, now))
            .and(part -> part.eq(FileObjectRow::getPurpose, "attachment")
                .eq(AgentMessageRow::getConversationId, conversation).eq(AgentMessageRow::getRole, "user")
                .or(output -> output.eq(FileObjectRow::getPurpose, "artifact")
                    .eq(AgentRunRow::getConversationId, conversation).eq(AgentRunRow::getUserId, user)));
    }
}
