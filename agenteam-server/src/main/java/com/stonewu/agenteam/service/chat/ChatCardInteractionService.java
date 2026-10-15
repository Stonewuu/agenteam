package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 平台只收到操作者提示，失败记录保留原因而不输出回调正文或临时令牌。 */
@Service
public class ChatCardInteractionService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatCardInteractionService.class);
    private final ChatCardInteractionTransactions transactions;

    public ChatCardInteractionService(ChatCardInteractionTransactions transactions) {
        this.transactions = transactions;
    }

    public JsonNode accept(String enterprise, String bot, Long lease, long credentialRevision, long bindingRevision, JsonNode event) {
        try {
            return transactions.accept(enterprise, bot, lease, credentialRevision, bindingRevision, event);
        } catch (RuntimeException failure) {
            LOG.warn("处理飞书卡片操作失败，企业={}，机器人={}", enterprise, bot,
                new ChannelProviderException("CHAT_CARD_ACTION_FAILED", "卡片操作未完成。", failure));
            String message = failure instanceof ApiException api ? "VERSION_CONFLICT".equals(api.code())
                ? "设置已发生变化，请重新打开回复方式后再选择。" : api.getMessage() : "暂时无法保存，请稍后重试或在群策中修改。";
            return ChatCardInteractionTransactions.toast("warning", message);
        }
    }

    public static boolean isCard(JsonNode event) {
        return "card.action.trigger".equals(event.path("header").path("event_type").asText());
    }
}
