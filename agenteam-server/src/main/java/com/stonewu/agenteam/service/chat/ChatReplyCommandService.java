package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.service.collaboration.CollaborationReplyModeService;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 仅接受独立、明确的设置指令，不从普通业务正文推断设置变化。 */
@Service
public class ChatReplyCommandService {
    private static final Map<String, String> TASK_MODES = Map.of(Messages.source("protocol.chat.chatReplyCommandService.TASK_MODES.1"), "summary_link", Messages.source("protocol.chat.chatReplyCommandService.TASK_MODES.2"), "summary_link",
        Messages.source("protocol.chat.chatReplyCommandService.TASK_MODES.3"), "chat_full", Messages.source("protocol.chat.chatReplyCommandService.TASK_MODES.4"), "chat_full");
    private static final Map<String, String> DEFAULT_MODES = Map.of(Messages.source("protocol.chat.chatReplyCommandService.DEFAULT_MODES.1"), "summary_link", Messages.source("protocol.chat.chatReplyCommandService.DEFAULT_MODES.2"), "chat_full");
    private final ChatReplyPreferenceService preferences;
    private final CollaborationReplyModeService modes;
    private final ChatOutboundService outbound;
    private final ChatPublicLinks links;

    public ChatReplyCommandService(ChatReplyPreferenceService preferences, CollaborationReplyModeService modes,
            ChatOutboundService outbound, ChatPublicLinks links) {
        this.preferences = preferences;
        this.modes = modes;
        this.outbound = outbound;
        this.links = links;
    }

    public Handled handle(AuthContext actor, ChatConversationRow group, String bot, ChatMessageRow message,
            String text, JsonNode mentions, String explicitTask, String currentTask) {
        String command = ChatCommandText.withoutLeadingMentions(text, mentions).strip()
            .replaceFirst(Messages.source("protocol.chat.chatReplyCommandService.handle.1"), "");
        if (!Messages.source("text.chat.chatReplyCommandService.handle.1").equals(command) && !TASK_MODES.containsKey(command) && !DEFAULT_MODES.containsKey(command)) {
            return null;
        }
        String key = "reply-mode:" + message.getId();
        if (("chat_full".equals(TASK_MODES.get(command)) || "chat_full".equals(DEFAULT_MODES.get(command)))
                && !"chat_full".equals(ChatReplyPolicy.defaultOutputVisibility(group))) {
            outbound.text(actor.enterpriseId(), group.getId(), bot, null, null, key, "reply_settings",
                Messages.text("text.chat.chatReplyCommandService.handle.2"), message.getPlatformMessageId());
            return new Handled(null);
        }
        if (Messages.source("text.chat.chatReplyCommandService.handle.1").equals(command)) {
            if (explicitTask != null) {
                var mode = modes.get(actor, explicitTask);
                outbound.settings(actor.enterpriseId(), group.getId(), bot, explicitTask, key,
                    Messages.text("text.chat.chatReplyCommandService.handle.3"), links.task(actor.enterpriseId(), explicitTask) + "?replyMode=edit", "chat_full".equals(mode.outputVisibility()));
                return new Handled(explicitTask);
            }
            var preference = preferences.get(actor, group.getId());
            outbound.settings(actor.enterpriseId(), group.getId(), bot, null, key, Messages.text("text.chat.chatReplyCommandService.handle.4"),
                links.replyPreferences(actor.enterpriseId(), group.getId()), "chat_full".equals(preference.outputVisibility()));
            return new Handled(null);
        }
        if (DEFAULT_MODES.containsKey(command)) {
            var preference = preferences.get(actor, group.getId());
            String visibility = DEFAULT_MODES.get(command);
            preferences.save(actor, group.getId(), visibility, preference.revision());
            outbound.text(actor.enterpriseId(), group.getId(), bot, null, null, key, "reply_settings",
                Messages.text("text.chat.chatReplyCommandService.handle.5", label(visibility)), null);
            return new Handled(null);
        }
        if (currentTask == null) {
            outbound.text(actor.enterpriseId(), group.getId(), bot, null, null, key, "reply_settings", Messages.text("text.chat.chatReplyCommandService.handle.6"), null);
            return new Handled(null);
        }
        var mode = modes.get(actor, currentTask);
        String visibility = TASK_MODES.get(command);
        modes.save(actor, currentTask, new CollaborationReplyModeRequest(visibility, mode.revision(), false, null));
        outbound.text(actor.enterpriseId(), group.getId(), bot, currentTask, null, key, "reply_settings", Messages.text("text.chat.chatReplyCommandService.handle.7", label(visibility)), null);
        return new Handled(currentTask);
    }

    private String label(String mode) {
        return "chat_full".equals(mode) ? Messages.text("text.chat.chatReplyCommandService.label.1") : Messages.text("text.chat.chatReplyCommandService.label.2");
    }

    public record Handled(String taskId) {
    }
}
