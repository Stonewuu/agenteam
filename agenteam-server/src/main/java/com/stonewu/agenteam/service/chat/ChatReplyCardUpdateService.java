package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 更新已有设置卡片，不另发群通知，也不撤回已经交付的回答。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChatReplyCardUpdateService {
    private final ChatOutboundMessageMapper messages;
    private final ChatOutboundService outbound;
    private final ChatConversationService conversations;
    private final ChatPayloadCipher cipher;

    public ChatReplyCardUpdateService(ChatOutboundMessageMapper messages, ChatOutboundService outbound,
            ChatConversationService conversations, ChatPayloadCipher cipher) {
        this.messages = messages;
        this.outbound = outbound;
        this.conversations = conversations;
        this.cipher = cipher;
    }

    public void changed(CollaborationTaskRow task) {
        if (!"feishu".equals(conversations.require(task.getEnterpriseId(), task.getConversationId()).getPlatformCode())) {
            return;
        }
        for (var row : messages.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
                .eq(ChatOutboundMessageRow::getEnterpriseId, task.getEnterpriseId()).eq(ChatOutboundMessageRow::getTaskId, task.getId())
                .eq(ChatOutboundMessageRow::getStatus, "sent").eq(ChatOutboundMessageRow::getPartNo, 0)
                .in(ChatOutboundMessageRow::getKind, "accepted", "reply_settings").isNotNull(ChatOutboundMessageRow::getPlatformMessageId))) {
            if (row.getRenderedPayloadEncrypted() != null && cipher.decrypt(row.getEnterpriseId(), "outbound-rendered", row.getId(),
                    row.getRenderedPayloadEncrypted()).hasNonNull("replySettingsUrl")) {
                outbound.updateSettingsCard(row, "reply-card:" + row.getId() + ":" + UUID.randomUUID(), false);
            }
        }
    }
}
