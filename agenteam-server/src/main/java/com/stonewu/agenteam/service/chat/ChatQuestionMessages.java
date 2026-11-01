package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

/** 完整问题与短按钮分开组织，避免企微客户端裁切问题和选项说明。 */
@Service
public class ChatQuestionMessages {
    private final ChatConversationService conversations;
    private final ChatOutboundService outbound;
    private final ChatOutboundMessageMapper messages;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;

    public ChatQuestionMessages(ChatConversationService conversations, ChatOutboundService outbound,
            ChatOutboundMessageMapper messages, ChatPayloadCipher cipher, ResourceJson json) {
        this.conversations = conversations;
        this.outbound = outbound;
        this.messages = messages;
        this.cipher = cipher;
        this.json = json;
    }

    @Transactional
    public void send(RunQuestionRow question, RunQuestionView view) {
        var group = conversations.require(question.getEnterpriseId(), question.getChatConversationId());
        boolean wecom = "wecom_aibot".equals(group.getPlatformCode());
        if (wecom) {
            var parts = outbound.textParts(question.getEnterpriseId(), group.getId(), question.getBotBindingId(), question.getTaskId(), null,
                "question-text:" + question.getId(), "user_question_text", text(view, "group".equals(group.getConversationKind())), null);
            parts.forEach(part -> update(part, payload -> payload.putObject("questionReference").put("id", view.id()).put("revision", view.revision())));
        }
        var row = outbound.text(question.getEnterpriseId(), group.getId(), question.getBotBindingId(), question.getTaskId(), null,
            "question-card:" + question.getId(), "user_question_card", "请选择或输入你的回答。", null);
        update(row, payload -> {
            payload.set("userQuestion", json.tree(view));
            payload.put("questionCardId", row.getId()).put("questionDirect", "direct".equals(group.getConversationKind()));
        });
    }

    private void update(ChatOutboundMessageRow row, Consumer<ObjectNode> change) {
        if (row.getFirstSubmittedAt() != null) {
            return;
        }
        ObjectNode payload = (ObjectNode) cipher.decrypt(row.getEnterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
        change.accept(payload);
        if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
                .eq(ChatOutboundMessageRow::getEnterpriseId, row.getEnterpriseId()).eq(ChatOutboundMessageRow::getId, row.getId())
                .isNull(ChatOutboundMessageRow::getFirstSubmittedAt)
                .set(ChatOutboundMessageRow::getPayloadEncrypted, cipher.encrypt(row.getEnterpriseId(), "outbound", row.getId(), payload))
                .set(ChatOutboundMessageRow::getPayloadHash, json.hash(payload))) != 1) {
            throw new IllegalStateException("提问消息保存失败");
        }
    }

    public static String text(RunQuestionView question, boolean group) {
        var text = new StringBuilder(question.question());
        int index = 0;
        for (var option : question.options()) {
            text.append("\n\n").append(++index).append(". ").append(option.label());
            if (option.recommended()) {
                text.append("（推荐）");
            }
            if (option.description() != null && !option.description().isBlank()) {
                text.append("\n").append(option.description());
            }
        }
        return text.append(group ? "\n\n可以直接 @ 我输入回答；要回答其他问题时，先点该问题卡片的“自己输入”。"
            : "\n\n可以直接输入回答；要回答其他问题时，先点该问题卡片的“自己输入”。").toString();
    }
}
