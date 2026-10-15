package com.stonewu.agenteam.service.integration.provider.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.response.IntegrationProviderView;
import com.stonewu.agenteam.service.chat.provider.wecom.WecomAiBotConnectionProvider;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** 专用机器人认证没有企业编号，不提供旧应用登录或个人通知能力。 */
@Component
public class WecomAiBotIntegrationProvider implements IntegrationProvider {
    private final WecomAiBotConnectionProvider connections;
    private final ChatBotBindingMapper bots;
    private final Clock clock;
    public WecomAiBotIntegrationProvider(WecomAiBotConnectionProvider connections, ChatBotBindingMapper bots, Clock clock) {
        this.connections = connections;
        this.bots = bots;
        this.clock = clock;
    }

    @Override
    public String code() {
        return "wecom_aibot";
    }

    @Override
    public String name() {
        return "企微智能机器人";
    }

    @Override
    public List<IntegrationProviderView.Field> publicFields() {
        return List.of(new IntegrationProviderView.Field("externalAppId", "机器人编号（Bot ID）", "text", true, null, 191, null, "填写智能机器人专用编号。"));
    }

    @Override
    public ChannelApplicationCheck verify(IntegrationApplication application, String secret) {
        if (bots.exists(new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, application.enterpriseId())
            .eq(ChatBotBindingRow::getConnectionId, application.id()).gt(ChatBotBindingRow::getLeaseUntil, clock.instant()))) {
            throw new ChannelProviderException("CHAT_CONNECTION_IN_USE", "请先停用此机器人并等待连接释放，再检查凭据。");
        }
        try (var connection = connections.open(application, secret, event -> {
            // 凭据检查不消费业务消息；只有正式接收工作才可以持久处理。
        })) {
            if (!connection.ready()) {
                throw new ChannelProviderException("CHAT_AUTHENTICATION_FAILED", "企微智能机器人尚未完成认证。");
            }
            return new ChannelApplicationCheck(null, "企微智能机器人");
        }
    }

    @Override
    public ChannelAccessToken fetchAccessToken(IntegrationApplication application, String secret) {
        throw unsupported();
    }

    @Override
    public ChannelApplicationCheck checkApplication(IntegrationApplication application, ChannelAccessToken token) {
        throw unsupported();
    }

    private ChannelProviderException unsupported() {
        return new ChannelProviderException("CHANNEL_CAPABILITY_UNAVAILABLE", "企微智能机器人使用专用长连接认证。");
    }
}
