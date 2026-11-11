package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationExecutionContext;
import com.stonewu.agenteam.service.chat.ChatConversationService;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import org.springframework.stereotype.Service;

/** 只回传智能体明确提出的问题与选项，不附带内部上下文或业务审批内容。 */
@Service
public class CollaborationQuestionNoticeService {
    private final WebRoomTimeline rooms;
    private final ChatMessageMapper messages;
    private final ChatConversationService conversations;
    private final ChatOutboundService outbound;

    public CollaborationQuestionNoticeService(ChatMessageMapper messages, ChatConversationService conversations, ChatOutboundService outbound, WebRoomTimeline rooms) {
        this.rooms = rooms;
        this.messages = messages;
        this.conversations = conversations;
        this.outbound = outbound;
    }

    public void send(CollaborationExecutionContext context, CollaborationActionRow action, String question, JsonNode options) {
        var task = context.task();
        if (task.fromWebRoom()) {
            var text = new StringBuilder(question);
            options.forEach(option -> text.append("\n• ").append(option.asText()));
            rooms.append(task.getEnterpriseId(), task.getWebRoomId(), task.getId(), context.participant().getId(),
                context.participant().getRoleName(), "action:" + action.getId(), "waiting_input", text.toString());
            return;
        }
        var group = conversations.require(task.getEnterpriseId(), task.getConversationId());
        boolean direct = group.getConversationKind().equals("direct");
        var text = new StringBuilder();
        if (!direct) {
            text.append(Messages.text("text.collaboration.collaborationQuestionNoticeService.send.1")).append(task.getShortCode()).append("】：【")
                .append(context.participant().getRoleName()).append(Messages.text("text.collaboration.collaborationQuestionNoticeService.send.2"));
        }
        text.append(question);
        int ordinal = 0;
        for (var option : options) {
            text.append("\n").append(++ordinal).append(". ").append(option.asText());
        }
        if (direct) {
            text.append(Messages.text("text.collaboration.collaborationQuestionNoticeService.send.3"));
        } else if (group.getPlatformCode().equals("feishu")) {
            text.append(Messages.text("text.collaboration.collaborationQuestionNoticeService.send.4"));
        } else {
            text.append(Messages.text("text.collaboration.collaborationQuestionNoticeService.send.5")).append(task.getShortCode()).append(Messages.text("text.collaboration.collaborationQuestionNoticeService.send.6"));
        }
        var origin = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, task.getEnterpriseId()).eq(ChatMessageRow::getConversationId, task.getConversationId())
            .eq(ChatMessageRow::getId, task.getOriginMessageId()));
        outbound.text(task.getEnterpriseId(), task.getConversationId(), context.participant().getBotBindingId(), task.getId(), action.getId(),
            "action:" + action.getId(), "waiting_input", text.toString(),
            group.getPlatformCode().equals("feishu") && origin != null ? origin.getPlatformMessageId() : null);
    }
}
