package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.chat.ChatSessionSelectionMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatSessionSelectionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** 调用方持有企业事务锁；切换只决定后续消息归属，不停止旧任务。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChatSessionSelectionService {
    private final ChatSessionSelectionMapper selections;
    private final CollaborationTaskMapper tasks;
    private final Clock clock;

    public ChatSessionSelectionService(ChatSessionSelectionMapper selections, CollaborationTaskMapper tasks, Clock clock) {
        this.selections = selections;
        this.tasks = tasks;
        this.clock = clock;
    }

    public ChatSessionSelectionRow forMessage(AuthContext actor, String conversation, String bot, Instant received, boolean startNew, String messageId) {
        if (startNew) {
            String id = UUID.nameUUIDFromBytes((actor.enterpriseId() + ":new-message:" + messageId).getBytes(StandardCharsets.UTF_8)).toString();
            var previous = selections.selectOne(scope(actor, conversation, bot).eq(ChatSessionSelectionRow::getId, id));
            return previous == null ? select(actor, conversation, bot, null, received, id) : previous;
        }
        var found = selections.selectPage(new Page<ChatSessionSelectionRow>(1, 1, false), scope(actor, conversation, bot)
            .le(ChatSessionSelectionRow::getSelectedAt, received)
            .orderByDesc(ChatSessionSelectionRow::getSelectedAt, ChatSessionSelectionRow::getSelectionNo)).getRecords();
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        // 升级前的对话也可以接着聊，不优先选取更早但仍未结束的任务。
        var previous = tasks.selectPage(new Page<CollaborationTaskRow>(1, 1, false), new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationTaskRow::getConversationId, conversation)
            .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId()).eq(CollaborationTaskRow::getEntryBotId, bot)
            .le(CollaborationTaskRow::getCreatedAt, received)
            .orderByDesc(CollaborationTaskRow::getCreatedAt, CollaborationTaskRow::getId)).getRecords();
        String task = previous.isEmpty() || "cancelled".equals(previous.getFirst().getStatus()) ? null : previous.getFirst().getId();
        return select(actor, conversation, bot, task, received);
    }

    public ChatSessionSelectionRow select(AuthContext actor, String conversation, String bot, String task, Instant at) {
        return select(actor, conversation, bot, task, at, UUID.randomUUID().toString());
    }

    private ChatSessionSelectionRow select(AuthContext actor, String conversation, String bot, String task, Instant at, String id) {
        var last = selections.selectPage(new Page<ChatSessionSelectionRow>(1, 1, false), scope(actor, conversation, bot)
            .orderByDesc(ChatSessionSelectionRow::getSelectionNo)).getRecords();
        var row = new ChatSessionSelectionRow();
        row.setId(id);
        row.setEnterpriseId(actor.enterpriseId());
        row.setConversationId(conversation);
        row.setUserId(actor.userId());
        row.setBotBindingId(bot);
        row.setTaskId(task);
        row.setSelectedAt(at);
        row.setRevision(1L);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        row.setSelectionNo(last.isEmpty() ? 1 : last.getFirst().getSelectionNo() + 1);
        if (selections.insert(row) != 1) {
            throw new IllegalStateException("对话选择未能保存");
        }
        return row;
    }

    public void bind(ChatSessionSelectionRow selection, CollaborationTaskRow task) {
        if (!Objects.equals(selection.getEnterpriseId(), task.getEnterpriseId())
            || !Objects.equals(selection.getConversationId(), task.getConversationId())
            || !Objects.equals(selection.getUserId(), task.getInitiatorUserId())
            || !Objects.equals(selection.getBotBindingId(), task.getEntryBotId())) {
            throw new IllegalArgumentException("任务不属于当前成员的机器人对话");
        }
        if (Objects.equals(selection.getTaskId(), task.getId())) {
            return;
        }
        if (selection.getTaskId() != null || selections.update(new LambdaUpdateWrapper<ChatSessionSelectionRow>()
            .eq(ChatSessionSelectionRow::getEnterpriseId, selection.getEnterpriseId()).eq(ChatSessionSelectionRow::getId, selection.getId())
            .isNull(ChatSessionSelectionRow::getTaskId).set(ChatSessionSelectionRow::getTaskId, task.getId())
            .eq(ChatSessionSelectionRow::getRevision, selection.getRevision()).setIncrBy(ChatSessionSelectionRow::getRevision, 1)
            .set(ChatSessionSelectionRow::getUpdatedAt, clock.instant())) != 1) {
            throw new IllegalStateException("当前对话已经关联其他任务");
        }
        selection.setTaskId(task.getId());
        selection.setRevision(selection.getRevision() + 1);
    }

    private LambdaQueryWrapper<ChatSessionSelectionRow> scope(AuthContext actor, String conversation, String bot) {
        return new LambdaQueryWrapper<ChatSessionSelectionRow>().eq(ChatSessionSelectionRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatSessionSelectionRow::getConversationId, conversation).eq(ChatSessionSelectionRow::getUserId, actor.userId())
            .eq(ChatSessionSelectionRow::getBotBindingId, bot);
    }
}
