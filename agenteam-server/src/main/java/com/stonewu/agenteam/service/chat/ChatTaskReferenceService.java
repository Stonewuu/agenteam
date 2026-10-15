package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.regex.Pattern;

/** 只使用已保存的回复、话题和任务编号，不按最后一条群消息猜测归属。 */
@Service
public class ChatTaskReferenceService {
    private static final Pattern CODE = Pattern.compile("(?<![A-Za-z0-9])GC-[A-Fa-f0-9]{12}(?![A-Za-z0-9])");
    private final ChatMessageMapper messages;
    private final ChatOutboundMessageMapper outgoing;
    private final CollaborationTaskMapper tasks;

    public ChatTaskReferenceService(ChatMessageMapper messages, ChatOutboundMessageMapper outgoing, CollaborationTaskMapper tasks) {
        this.messages = messages;
        this.outgoing = outgoing;
        this.tasks = tasks;
    }

    public Optional<String> resolve(ChatMessageRow message, String text) {
        var found = new LinkedHashSet<String>();
        if (message.getReplyToPlatformId() != null) {
            messages.selectList(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, message.getEnterpriseId())
                .eq(ChatMessageRow::getConversationId, message.getConversationId()).eq(ChatMessageRow::getPlatformMessageId, message.getReplyToPlatformId())
                .isNotNull(ChatMessageRow::getTaskId)).forEach(row -> found.add(row.getTaskId()));
            outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, message.getEnterpriseId())
                .eq(ChatOutboundMessageRow::getConversationId, message.getConversationId()).eq(ChatOutboundMessageRow::getPlatformMessageId, message.getReplyToPlatformId())
                .isNotNull(ChatOutboundMessageRow::getTaskId)).forEach(row -> found.add(row.getTaskId()));
        }
        if (message.getThreadId() != null && found.isEmpty()) {
            messages.selectList(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, message.getEnterpriseId())
                .eq(ChatMessageRow::getConversationId, message.getConversationId()).eq(ChatMessageRow::getThreadId, message.getThreadId())
                .isNotNull(ChatMessageRow::getTaskId)).forEach(row -> found.add(row.getTaskId()));
            outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, message.getEnterpriseId())
                .eq(ChatOutboundMessageRow::getConversationId, message.getConversationId()).eq(ChatOutboundMessageRow::getPlatformThreadId, message.getThreadId())
                .isNotNull(ChatOutboundMessageRow::getTaskId)).forEach(row -> found.add(row.getTaskId()));
        }
        var matcher = CODE.matcher(text);
        while (matcher.find()) {
            var matches = tasks.selectList(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, message.getEnterpriseId())
                .eq(CollaborationTaskRow::getConversationId, message.getConversationId()).eq(CollaborationTaskRow::getShortCode, matcher.group().toUpperCase()));
            if (matches.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_TASK_REFERENCE_INVALID", "此会话中没有这个任务，请检查任务编号。");
            }
            matches.forEach(row -> found.add(row.getId()));
        }
        if (found.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_TASK_REFERENCE_AMBIGUOUS", "消息关联了多个任务，请只回复其中一个任务。");
        }
        return found.stream().findFirst();
    }
}
