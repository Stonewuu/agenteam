package com.stonewu.agenteam.service.chat;

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
    private static final Map<String, String> TASK_MODES = Map.of("本次只发链接", "summary_link", "本次仅发送链接", "summary_link",
        "本次直接回复", "chat_full", "本次直接回复内容", "chat_full");
    private static final Map<String, String> DEFAULT_MODES = Map.of("默认仅发送链接", "summary_link", "默认直接回复内容", "chat_full");
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
            .replaceFirst("(?i)^(?:任务\\s*)?(?:【GC-[A-F0-9]{12}】|GC-[A-F0-9]{12})\\s*[:：+]?\\s*", "");
        if (!"回复方式".equals(command) && !TASK_MODES.containsKey(command) && !DEFAULT_MODES.containsKey(command)) {
            return null;
        }
        String key = "reply-mode:" + message.getId();
        if (("chat_full".equals(TASK_MODES.get(command)) || "chat_full".equals(DEFAULT_MODES.get(command)))
                && !"chat_full".equals(ChatReplyPolicy.defaultOutputVisibility(group))) {
            outbound.text(actor.enterpriseId(), group.getId(), bot, null, null, key, "reply_settings",
                "本群已设为仅发送链接。如需直接回复内容，请联系管理员调整群内回复限制。", message.getPlatformMessageId());
            return new Handled(null);
        }
        if ("回复方式".equals(command)) {
            if (explicitTask != null) {
                var mode = modes.get(actor, explicitTask);
                outbound.settings(actor.enterpriseId(), group.getId(), bot, explicitTask, key,
                    "选择本任务的回复方式。", links.task(actor.enterpriseId(), explicitTask) + "?replyMode=edit", "chat_full".equals(mode.outputVisibility()));
                return new Handled(explicitTask);
            }
            var preference = preferences.get(actor, group.getId());
            outbound.settings(actor.enterpriseId(), group.getId(), bot, null, key, "设置你在此会话的默认回复方式。",
                links.replyPreferences(actor.enterpriseId(), group.getId()), "chat_full".equals(preference.outputVisibility()));
            return new Handled(null);
        }
        if (DEFAULT_MODES.containsKey(command)) {
            var preference = preferences.get(actor, group.getId());
            String visibility = DEFAULT_MODES.get(command);
            preferences.save(actor, group.getId(), visibility, preference.revision());
            outbound.text(actor.enterpriseId(), group.getId(), bot, null, null, key, "reply_settings",
                "已将你在此会话的默认方式设为“" + label(visibility) + "”。", null);
            return new Handled(null);
        }
        if (currentTask == null) {
            outbound.text(actor.enterpriseId(), group.getId(), bot, null, null, key, "reply_settings", "请回复需要设置的任务消息，再发送这条设置指令。", null);
            return new Handled(null);
        }
        var mode = modes.get(actor, currentTask);
        String visibility = TASK_MODES.get(command);
        modes.save(actor, currentTask, new CollaborationReplyModeRequest(visibility, mode.revision(), false, null));
        outbound.text(actor.enterpriseId(), group.getId(), bot, currentTask, null, key, "reply_settings", "本任务已设为“" + label(visibility) + "”。", null);
        return new Handled(currentTask);
    }

    private String label(String mode) {
        return "chat_full".equals(mode) ? "直接回复内容" : "仅发送链接";
    }

    public record Handled(String taskId) {
    }
}
