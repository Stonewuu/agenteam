package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuWebhookVerifier;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 公开定位键只选择配置；所有回调仍需独立认证。 */
@Service
public class ChatWebhookService {
    private final ChatBotBindingMapper rows;
    private final ChatBotBindingService bots;
    private final FeishuWebhookVerifier verifier;
    private final IntegrationSecretService secrets;
    private final ChatIngressService ingress;
    private final ChatCardInteractionService cards;
    private final ResourceJson json;
    public ChatWebhookService(ChatBotBindingMapper rows, ChatBotBindingService bots, FeishuWebhookVerifier verifier,
            IntegrationSecretService secrets, ChatIngressService ingress, ChatCardInteractionService cards, ResourceJson json) {
        this.rows = rows;
        this.bots = bots;
        this.verifier = verifier;
        this.secrets = secrets;
        this.ingress = ingress;
        this.cards = cards;
        this.json = json;
    }

    public Map<String, Object> accept(String key, byte[] raw, String timestamp, String nonce, String signature) {
        var bot = key.matches("[a-f0-9]{32}") ? rows.selectOne(new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getPublicCallbackKey, key).eq(ChatBotBindingRow::getTransport, "webhook")
            .eq(ChatBotBindingRow::getStatus, "enabled")) : null;
        if (bot == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_CALLBACK_UNAVAILABLE", "平台回调入口不可用。");
        }
        var app = bots.application(bot);
        if (!app.providerCode().equals("feishu")) {
            throw ChatPlatformRegistry.unavailable();
        }
        var event = verifier.verify(raw, timestamp, nonce, signature, secrets.load(app.enterpriseId(), app.id(), "chat_verification_token"),
            secrets.load(app.enterpriseId(), app.id(), "chat_encrypt_key"));
        if (event.path("type").asText().equals("url_verification")) {
            return Map.of("challenge", event.path("challenge").asText());
        }
        if (ChatCardInteractionService.isCard(event)) {
            return json.object(cards.accept(app.enterpriseId(), bot.getId(), null, app.credentialRevision(), bot.getRevision(), event));
        }
        ingress.acceptVerified(app.enterpriseId(), bot.getId(), null, app.credentialRevision(), bot.getRevision(), "webhook", event);
        return Map.of("msg", "success");
    }
}
