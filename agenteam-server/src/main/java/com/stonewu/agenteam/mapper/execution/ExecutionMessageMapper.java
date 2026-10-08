package com.stonewu.agenteam.mapper.execution;


import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionMessageQueryRow;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.MessageView;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 消息正文和块只在数据库事务中修改，历史读取不依赖框架状态目录。
 */
@Repository
public class ExecutionMessageMapper {
    private final ExecutionMessageSqlMapper statements;
    private final ExecutionJson json;
    private final MessageAttachmentMapper attachments;
    private final MessageFeedbackTableMapper messageFeedbackTableMapper;

    public ExecutionMessageMapper(ExecutionMessageSqlMapper statements, ExecutionJson json,
                                  MessageAttachmentMapper attachments,
                                  MessageFeedbackTableMapper messageFeedbackTableMapper) {
        this.messageFeedbackTableMapper = messageFeedbackTableMapper;
        this.statements = statements;
        this.json = json;
        this.attachments = attachments;
    }

    public void create(String enterprise, String conversation, String id, String role, MessageInput input,
                       List<ContentBlock> blocks, Instant now) {
        create(enterprise, conversation, id, role, input, blocks, role.equals("user") ? 0 : 1, now);
    }

    public void create(String enterprise, String conversation, String id, String role, MessageInput input,
                       List<ContentBlock> blocks, int attemptNo, Instant now) {
        statements.createAgentMessage(id, enterprise, conversation, role, input == null ? "" : input.text(),
            role.equals("user") ? "completed" : "pending", attemptNo, json.write(blocks), json.write(input),
            timestamp(now));
    }

    public void associate(String enterprise, String id, String run) {
        statements.associateAgentMessage(run, enterprise, id);
    }

    public Optional<MessageView> find(String enterprise, String conversation, String id) {
        return withAttachments(enterprise,
            statements.findAgentMessage(enterprise, conversation, id).stream().map(this::map).toList()).stream()
            .findFirst();
    }

    public Optional<ContentBlock> savedToolBlock(String enterprise, String conversation, String message, String step) {
        return statements.findAgentMessage(enterprise, conversation, message).stream()
            .flatMap(row -> json.blocks(row.getBlocksJson()).stream())
            .filter(block -> step.equals(block.stepId()) && block.tool() != null).findFirst();
    }

    public MessageInput input(String enterprise, String id) {
        String value = DataAccessUtils.nullableSingleResult(statements.inputAgentMessage(enterprise, id));
        var node = json.tree(value);
        var skills = new ArrayList<String>();
        node.path("skillVersionIds").forEach(item -> skills.add(item.asText()));
        var files = new ArrayList<String>();
        node.path("attachmentIds").forEach(item -> files.add(item.asText()));
        var links = new ArrayList<String>();
        node.path("links").forEach(item -> links.add(item.asText()));
        var references = new ArrayList<MessageInput.KnowledgeReference>();
        node.path("knowledgeReferences").forEach(item -> references.add(
            new MessageInput.KnowledgeReference(item.path("documentId").asText(), item.path("generation").asInt())));
        return new MessageInput(node.path("text").asText(), files, skills, references, links);
    }

    /**
     * 倒序读取有限页，调用方按时间正序展示；同一提交内输入排在输出之前。
     */
    public List<MessageView> history(String enterprise, String conversation, MessageView before, int limit) {
        var beforeTime = before == null ? null : Instant.parse(before.createdAt());
        int beforeRole = before != null && before.role().equals("user") ? 0 : 1;
        var rows = statements.historyMessages(enterprise, conversation, beforeTime, beforeRole,
            before == null ? null : before.id(), limit);
        return withAttachments(enterprise, rows.stream().map(this::map).toList());
    }

    private List<MessageView> withAttachments(String enterprise, List<MessageView> messages) {
        var files = attachments.forMessages(enterprise, messages.stream().map(MessageView::id).toList());
        return messages.stream().map(
            message -> new MessageView(message.id(), message.runId(), message.attemptNo(), message.role(),
                message.content(), message.status(),
                message.blocks(), List.copyOf(files.getOrDefault(message.id(), List.of())), message.feedback(),
                message.createdAt(), message.updatedAt())).toList();
    }

    public void save(String enterprise, String id, String content, List<ContentBlock> blocks, String status,
                     long sequence, Instant now) {
        statements.saveAgentMessage(content, json.write(blocks), status, sequence, timestamp(now), enterprise, id);
    }

    public void status(String enterprise, String id, String status, long sequence, Instant now) {
        statements.statusAgentMessage(status, sequence, timestamp(now), enterprise, id);
    }

    public Optional<String> ownedConversation(String enterprise, String user, String id) {
        return statements.selectJoinList(AgentMessageRow.class, JoinWrappers.lambda(AgentMessageRow.class)
                .select(AgentMessageRow::getConversationId)
                .innerJoin(AgentConversationRow.class, on -> on
                    .eq(AgentConversationRow::getEnterpriseId, AgentMessageRow::getEnterpriseId)
                    .eq(AgentConversationRow::getId, AgentMessageRow::getConversationId))
                .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentMessageRow::getId, id)
                .eq(AgentConversationRow::getUserId, user).ne(AgentConversationRow::getStatus, "deleted"))
            .stream().map(AgentMessageRow::getConversationId).findFirst();
    }

    public void feedback(String enterprise, String user, String message, String value, String comment, Instant now) {
        if (value == null) {
            messageFeedbackTableMapper.feedbackMessageFeedback(enterprise, user, message);
        } else {
            statements.feedbackMessageFeedback2(enterprise, user, message, value, comment, timestamp(now));
        }
    }

    private MessageView map(ExecutionMessageQueryRow row) {
        return new MessageView(row.getId(), row.getRunId(), row.getAttemptNo(), row.getRole(),
            row.getContent(), row.getStatus(), json.blocks(row.getBlocksJson()), List.of(), row.getFeedback(),
            instant(row.getCreatedAt()).toString(), instant(row.getUpdatedAt()).toString());
    }
}
