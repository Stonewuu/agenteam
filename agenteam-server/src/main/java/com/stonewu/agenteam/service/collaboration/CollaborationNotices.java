package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import org.springframework.stereotype.Service;

/** 在任务来源展示同一项业务变化，网页任务不会排队向外部平台发送。 */
@Service
public class CollaborationNotices {
    private final WebRoomTimeline timeline;
    private final ChatOutboundService outbound;

    public CollaborationNotices(WebRoomTimeline timeline, ChatOutboundService outbound) {
        this.timeline = timeline;
        this.outbound = outbound;
    }

    public void send(CollaborationTaskRow task, CollaborationParticipantRow role, String action, String key,
            String kind, String text, boolean progress, String replyTo) {
        if (task.fromWebRoom()) {
            timeline.append(task.getEnterpriseId(), task.getWebRoomId(), task.getId(), role == null ? null : role.getId(),
                role == null ? Messages.text("text.collaboration.collaborationNotices.send.1") : role.getRoleName(), key, kind, text);
        } else if (progress) {
            outbound.progress(task.getEnterpriseId(), task.getConversationId(), role == null ? task.getEntryBotId() : role.getBotBindingId(),
                task.getId(), action, key, kind, text, replyTo);
        } else {
            outbound.text(task.getEnterpriseId(), task.getConversationId(), role == null ? task.getEntryBotId() : role.getBotBindingId(),
                task.getId(), action, key, kind, text, replyTo);
        }
    }
}
