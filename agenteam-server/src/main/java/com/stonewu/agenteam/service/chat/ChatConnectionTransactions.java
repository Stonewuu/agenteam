package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** 数据库保存连接归属；续约不改变用户编辑用的配置版本。 */
@Service
public class ChatConnectionTransactions {
    public record Lease(String enterprise, String botId, String owner, long version, long configurationRevision, IntegrationApplication application) {
    }
    private final ChatBotBindingMapper bots;
    private final EnterpriseIntegrationMapper apps;
    private final EnterpriseMapper enterprises;
    private final ResourceJson json;
    private final Clock clock;

    public ChatConnectionTransactions(ChatBotBindingMapper bots, EnterpriseIntegrationMapper apps, EnterpriseMapper enterprises,
            ResourceJson json, Clock clock) {
        this.bots = bots;
        this.apps = apps;
        this.enterprises = enterprises;
        this.json = json;
        this.clock = clock;
    }

    public List<ChatBotBindingRow> candidates() {
        return bots.selectPage(new Page<ChatBotBindingRow>(1, 100, false), new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getStatus, "enabled").eq(ChatBotBindingRow::getTransport, "websocket")
            .and(value -> value.isNull(ChatBotBindingRow::getLeaseUntil).or().le(ChatBotBindingRow::getLeaseUntil, clock.instant()))
            .orderByAsc(ChatBotBindingRow::getUpdatedAt, ChatBotBindingRow::getId)).getRecords();
    }

    @Transactional
    public Optional<Lease> claim(String enterprise, String id, String owner) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var bot = bot(enterprise, id);
        var app = app(bot);
        if (!enabled(bot, app) || bot.getLeaseUntil() != null && bot.getLeaseUntil().isAfter(clock.instant())
            || json.read(bot.getConfigJson()).path("connectionStoppedRevision").asLong(-1) == bot.getRevision()) {
            return Optional.empty();
        }
        long version = bot.getLeaseVersion() + 1;
        int changed = bots.update(scope(bot).eq(ChatBotBindingRow::getLeaseVersion, bot.getLeaseVersion())
            .set(ChatBotBindingRow::getLeaseOwner, owner).set(ChatBotBindingRow::getLeaseVersion, version)
            .set(ChatBotBindingRow::getLeaseUntil, clock.instant().plusSeconds(30)));
        return changed == 1 ? Optional.of(new Lease(enterprise, id, owner, version, bot.getRevision(), application(app))) : Optional.empty();
    }

    @Transactional
    public boolean renew(Lease lease) {
        enterprises.lockEnterprise(lease.enterprise()).orElseThrow();
        var bot = bot(lease.enterprise(), lease.botId());
        var app = app(bot);
        if (!owned(bot, lease) || !enabled(bot, app) || !application(app).equals(lease.application())) {
            return false;
        }
        return bots.update(guard(lease).set(ChatBotBindingRow::getLeaseUntil, clock.instant().plusSeconds(30))) == 1;
    }

    public void require(Lease lease) {
        var bot = bot(lease.enterprise(), lease.botId());
        var app = app(bot);
        if (!owned(bot, lease) || !enabled(bot, app) || !application(app).equals(lease.application())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CONNECTION_EXPIRED", "当前机器人连接已失效。");
        }
    }

    @Transactional
    public void connected(Lease lease) {
        require(lease);
        bots.update(guard(lease).set(ChatBotBindingRow::getLastConnectedAt, clock.instant()));
    }

    @Transactional
    public void release(Lease lease, boolean replaced) {
        enterprises.lockEnterprise(lease.enterprise()).orElseThrow();
        var bot = bot(lease.enterprise(), lease.botId());
        var update = guard(lease).set(ChatBotBindingRow::getLeaseUntil, null).set(ChatBotBindingRow::getLeaseOwner, null);
        if (replaced && bot.getRevision() == lease.configurationRevision()) {
            var settings = json.read(bot.getConfigJson()).deepCopy();
            if (settings instanceof ObjectNode object) {
                object.put("connectionStoppedRevision", lease.configurationRevision());
                object.put("connectionError", "CHAT_CONNECTION_REPLACED");
                update.set(ChatBotBindingRow::getConfigJson, json.write(object));
            }
        }
        bots.update(update);
    }

    private boolean owned(ChatBotBindingRow bot, Lease lease) {
        return bot.getLeaseVersion() == lease.version() && lease.owner().equals(bot.getLeaseOwner())
            && bot.getLeaseUntil() != null && bot.getLeaseUntil().isAfter(clock.instant())
            && bot.getRevision() == lease.configurationRevision();
    }

    private boolean enabled(ChatBotBindingRow bot, EnterpriseIntegrationRow app) {
        return app != null && ChatPlatformRegistry.supportsProvider(app.getProviderCode()) && bot.getStatus().equals("enabled")
            && app.getStatus().equals("enabled") && bot.getTransport().equals("websocket");
    }

    private ChatBotBindingRow bot(String enterprise, String id) {
        var bot = bots.selectOne(new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise).eq(ChatBotBindingRow::getId, id));
        if (bot == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_BOT_UNAVAILABLE", "机器人绑定不存在。");
        }
        return bot;
    }

    private EnterpriseIntegrationRow app(ChatBotBindingRow bot) {
        return apps.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, bot.getEnterpriseId())
            .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()));
    }

    private IntegrationApplication application(EnterpriseIntegrationRow app) {
        return new IntegrationApplication(app.getId(), app.getEnterpriseId(), app.getProviderCode(), app.getExternalTenantId(), app.getExternalAppId(), app.getCredentialRevision());
    }

    private LambdaUpdateWrapper<ChatBotBindingRow> scope(ChatBotBindingRow bot) {
        return new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, bot.getEnterpriseId()).eq(ChatBotBindingRow::getId, bot.getId());
    }

    private LambdaUpdateWrapper<ChatBotBindingRow> guard(Lease lease) {
        return new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, lease.enterprise())
            .eq(ChatBotBindingRow::getId, lease.botId()).eq(ChatBotBindingRow::getLeaseOwner, lease.owner())
            .eq(ChatBotBindingRow::getLeaseVersion, lease.version());
    }
}
