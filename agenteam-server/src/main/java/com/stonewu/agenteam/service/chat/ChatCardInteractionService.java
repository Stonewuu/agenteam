package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Clock;

/** 平台只收到操作者提示，失败记录保留原因而不输出回调正文或临时令牌。 */
@Service
public class ChatCardInteractionService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatCardInteractionService.class);
    private final ChatCardInteractionTransactions transactions;
    private final Clock clock;

    public ChatCardInteractionService(ChatCardInteractionTransactions transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    public JsonNode accept(String enterprise, String bot, Long lease, long credentialRevision, long bindingRevision, JsonNode event) {
        var timing = new ChatCallbackTiming(clock);
        String outcome = Messages.log("text.chat.chatCardInteractionService.accept.1");
        try {
            return transactions.accept(enterprise, bot, lease, credentialRevision, bindingRevision, event, timing);
        } catch (RuntimeException failure) {
            outcome = failure instanceof ApiException api ? api.code() : Messages.log("text.chat.chatCardInteractionService.accept.2");
            LOG.warn(Messages.log("log.chat.chatCardInteractionService.accept.1"), enterprise, bot,
                new ChannelProviderException("CHAT_CARD_ACTION_FAILED", Messages.log("error.chat.chatCardInteractionService.accept.1"), failure));
            String message = failure instanceof ApiException api ? "VERSION_CONFLICT".equals(api.code())
                ? Messages.text("text.chat.chatCardInteractionService.accept.3") : api.getMessage() : Messages.text("text.chat.chatCardInteractionService.accept.4");
            return ChatCardInteractionTransactions.toast("warning", message);
        } finally {
            var measured = timing.finish();
            LOG.info(Messages.log("log.chat.chatCardInteractionService.accept.2"),
                enterprise, bot, ChatCallbackTiming.eventId(event.path("header").path("event_id").asText()), outcome,
                measured.totalMillis(), measured.stages());
        }
    }

    public static boolean isCard(JsonNode event) {
        return "card.action.trigger".equals(event.path("header").path("event_type").asText());
    }
}
