package com.stonewu.agenteam.service.integration.feishu;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationPublicUrls;
import com.stonewu.agenteam.model.integration.entity.FeishuSetupJobRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/** 一次领取只执行一个阶段，发布重试使用同一版本号并先核对平台结果。 */
@Service
public class FeishuSetupProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuSetupProcessor.class);
    private final FeishuSetupStore store;
    private final FeishuSetupTransactions transactions;
    private final FeishuAppClient client;
    private final IntegrationQueryService integrations;
    private final IntegrationSecretService credentials;
    private final FeishuManagementTokens managementTokens;
    private final IntegrationPublicUrls urls;
    private final ChatBotBindingService bots;
    private final ObjectMapper json;
    private final Clock clock;

    public FeishuSetupProcessor(FeishuSetupStore store, FeishuSetupTransactions transactions, FeishuAppClient client,
            IntegrationQueryService integrations, IntegrationSecretService credentials, FeishuManagementTokens managementTokens,
            IntegrationPublicUrls urls, ChatBotBindingService bots, ObjectMapper json, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.client = client;
        this.integrations = integrations;
        this.credentials = credentials;
        this.managementTokens = managementTokens;
        this.urls = urls;
        this.bots = bots;
        this.json = json;
        this.clock = clock;
    }

    public void process(FeishuSetupJobRow row) {
        String stage = row.getStage();
        var claimed = store.copy(row);
        try {
            store.actor(row);
            row.setErrorCode(null);
            row.setErrorSummary(null);
            row.setNextAttemptAt(clock.instant());
            switch (stage) {
                case "queued" -> begin(row);
                case "awaiting_scan" -> poll(row);
                case "preparing" -> prepare(row);
                case "configuring" -> configure(row);
                case "publishing" -> publish(row);
                case "waiting_approval" -> approved(row);
                case "binding" -> bind(row);
                default -> throw new IllegalStateException(Messages.text("error.integration.feishuSetupProcessor.process.1"));
            }
        } catch (RuntimeException failure) {
            LOG.warn(Messages.log("log.integration.feishuSetupProcessor.process.1"), row.getEnterpriseId(), row.getId(), stage, failure);
            // 已取得外部结果时保存下一步，不能再用已消费的注册凭据轮询。
            row.setResumeStage(row.getStage());
            String code = failure instanceof ChannelProviderException known ? known.code()
                : failure instanceof ApiException known ? known.code() : "FEISHU_SETUP_FAILED";
            row.setErrorCode(code);
            row.setErrorSummary(failure instanceof ChannelProviderException ? failure.getMessage()
                : failure instanceof ApiException known ? known.getReason() : Messages.text("error.integration.feishuSetupProcessor.process.2"));
            if (code.equals("FEISHU_AUTHORIZATION_REQUIRED")) {
                row.setStage("authorization_required");
            } else if (row.getResumeStage().equals("awaiting_scan") && row.getRegistrationExpiresAt() != null
                    && !row.getRegistrationExpiresAt().isAfter(clock.instant())) {
                row.setStage("registration_unknown");
                row.setResumeStage(null);
                row.setErrorSummary(Messages.text("error.integration.feishuSetupProcessor.process.3"));
            } else if (!List.of("expired", "cancelled", "registration_unknown").contains(row.getStage())) {
                row.setStage("failed");
            }
            try {
                if (store.restoreClaim(row, claimed)) {
                    store.save(row);
                }
            } catch (RuntimeException persistenceFailure) {
                LOG.error(Messages.log("log.integration.feishuSetupProcessor.process.2"), row.getId(), persistenceFailure);
            }
        }
    }

    private void begin(FeishuSetupJobRow row) {
        if (row.getKind().equals("bot")) {
            managementTokens.get(store.manager(row.getEnterpriseId(), row.getManagerConnectionId()), row.getOwnerUserId());
        }
        var input = store.input(row);
        var started = client.begin(row.getId(), row.getKind(), input.name(), input.description());
        store.material(row, started.material());
        row.setRegistrationExpiresAt(clock.instant().plusSeconds(started.expiresIn()));
        row.setPollInterval(started.interval());
        row.setNextAttemptAt(clock.instant().plusSeconds(started.interval()));
        row.setStage("awaiting_scan");
        store.save(row);
    }

    private void poll(FeishuSetupJobRow row) {
        var material = store.material(row);
        var result = client.poll(row.getId(), material.deviceCode());
        switch (result.state()) {
            case "created" -> {
                store.material(row, new FeishuSetupCipher.Registration(null, null, result.appId(), result.secret()));
                row.setStage("preparing");
            }
            case "expired_token" -> {
                row.setStage("expired");
                row.setEncryptedJson(null);
            }
            case "access_denied" -> {
                row.setStage("cancelled");
                row.setEncryptedJson(null);
            }
            default -> {
                if (!row.getRegistrationExpiresAt().isAfter(clock.instant())) {
                    row.setStage("registration_unknown");
                    row.setErrorSummary(Messages.text("error.integration.feishuSetupProcessor.poll.1"));
                } else {
                    if (result.state().equals("slow_down")) {
                        row.setPollInterval(Math.min(120, row.getPollInterval() + 5));
                    }
                    row.setNextAttemptAt(clock.instant().plusSeconds(row.getPollInterval()));
                }
            }
        }
        store.save(row);
    }

    private void prepare(FeishuSetupJobRow row) {
        if (row.getConnectionId() != null) {
            var existing = connection(row);
            String token = appToken(row, existing);
            client.requireSelfManagement(row.getId(), token);
            var tenant = client.get(row.getId(), "/open-apis/tenant/v2/tenant/query", Map.of(), token).path("data").path("tenant");
            String tenantId = FeishuAppClient.required(tenant, "tenant_key");
            if (existing.getExternalTenantId() != null && !existing.getExternalTenantId().equals(tenantId)) {
                throw new ChannelProviderException("FEISHU_TENANT_MISMATCH", Messages.text("error.integration.feishuSetupProcessor.prepare.1"));
            }
            String name = FeishuAppClient.required(client.app(row.getId(), existing.getExternalAppId(), token), "app_name");
            transactions.prepareExisting(row, store.actor(row), tenantId, FeishuAppClient.required(tenant, "name"), name);
            return;
        }
        var material = store.material(row);
        String token = client.token(row.getId(), material.appId(), material.secret());
        var tenant = client.get(row.getId(), "/open-apis/tenant/v2/tenant/query", Map.of(), token).path("data").path("tenant");
        String tenantId = FeishuAppClient.required(tenant, "tenant_key");
        if (row.getKind().equals("bot") && !store.manager(row.getEnterpriseId(), row.getManagerConnectionId()).getExternalTenantId().equals(tenantId)) {
            throw new ChannelProviderException("FEISHU_TENANT_MISMATCH", Messages.text("error.integration.feishuSetupProcessor.prepare.2"));
        }
        String confirmedName = FeishuAppClient.required(client.app(row.getId(), material.appId(), token), "app_name");
        transactions.install(row, store.actor(row), tenantId, FeishuAppClient.required(tenant, "name"), confirmedName);
    }

    private void configure(FeishuSetupJobRow row) {
        var connection = connection(row);
        String token = mutationToken(row, connection);
        String base = "/open-apis/application/v7/applications/" + connection.getExternalAppId();
        client.patch(row.getId(), base + "/ability", json.createObjectNode().set("bot", json.createObjectNode().put("enable", true)), token);
        // 名称、头像和简介由用户在飞书确认，后续只配置接入所需的能力。
        var callback = row.getKind().equals("management") ? urls.feishuManagementCallback(connection.getId()) : urls.callback(connection.getId());
        client.patch(row.getId(), base + "/config", client.configuration(row.getKind(), callback, urls.feishuManagementCallback(connection.getId())), token);
        row.setStage("publishing");
        store.save(row);
    }

    private void publish(FeishuSetupJobRow row) {
        var connection = connection(row);
        String own = appToken(row, connection);
        var app = client.app(row.getId(), connection.getExternalAppId(), own);
        String online = app.path("online_version_id").asText("");
        String pending = app.path("unaudit_version_id").asText("");
        if (row.getExternalVersion() == null) {
            if (!pending.isBlank()) {
                throw new ChannelProviderException("FEISHU_APP_UNDER_REVIEW", Messages.text("error.integration.feishuSetupProcessor.publish.1"));
            }
            String previous = online.isBlank() ? "0.0.0" : client.version(row.getId(), connection.getExternalAppId(), online, own).path("version").asText();
            row.setExternalVersion(nextVersion(previous));
            store.save(row);
            return;
        }
        for (String candidate : List.of(online, pending)) {
            if (!candidate.isBlank() && row.getExternalVersion().equals(client.version(row.getId(), connection.getExternalAppId(), candidate, own).path("version").asText())) {
                row.setExternalVersionId(candidate);
                row.setStage("waiting_approval");
                store.save(row);
                return;
            }
        }
        if (!pending.isBlank()) {
            throw new ChannelProviderException("FEISHU_APP_UNDER_REVIEW", Messages.text("error.integration.feishuSetupProcessor.publish.2"));
        }
        var body = json.createObjectNode().put("mobile_default_ability", "bot").put("pc_default_ability", "bot")
            .put("remark", row.getKind().equals("enterprise") ? Messages.text("text.integration.feishuSetupProcessor.publish.1") : Messages.text("text.integration.feishuSetupProcessor.publish.2"))
            .put("changelog", row.getKind().equals("enterprise") ? Messages.text("text.integration.feishuSetupProcessor.publish.3") : Messages.text("text.integration.feishuSetupProcessor.publish.4"))
            .put("version", row.getExternalVersion());
        var result = client.post(row.getId(), "/open-apis/application/v7/applications/" + connection.getExternalAppId() + "/publish",
            body, mutationToken(row, connection)).path("data");
        row.setExternalVersionId(FeishuAppClient.required(result, "version_id"));
        row.setStage("waiting_approval");
        row.setNextAttemptAt(clock.instant().plusSeconds(5));
        store.save(row);
    }

    private void approved(FeishuSetupJobRow row) {
        var connection = connection(row);
        String token = appToken(row, connection);
        var app = client.app(row.getId(), connection.getExternalAppId(), token);
        var version = client.version(row.getId(), connection.getExternalAppId(), row.getExternalVersionId(), token);
        if (version.path("status").asInt() == 2) {
            row.setExternalVersion(nextVersion(row.getExternalVersion()));
            row.setExternalVersionId(null);
            row.setResumeStage("configuring");
            row.setStage("failed");
            row.setErrorCode("FEISHU_RELEASE_REJECTED");
            row.setErrorSummary(Messages.text("error.integration.feishuSetupProcessor.approved.1"));
        } else if (row.getExternalVersionId().equals(app.path("online_version_id").asText()) && version.path("status").asInt() == 1) {
            client.requireScopes(row.getId(), token, row.getKind());
            transactions.approved(row, store.actor(row));
            return;
        } else {
            row.setNextAttemptAt(clock.instant().plusSeconds(15));
        }
        store.save(row);
    }

    private void bind(FeishuSetupJobRow row) {
        var actor = store.actor(row);
        var prepared = bots.prepare(actor, row.getEnterpriseId(), row.getSystemAccess(), row.getConnectionId());
        transactions.bind(row, actor, prepared);
    }

    private EnterpriseIntegrationRow connection(FeishuSetupJobRow row) {
        var value = integrations.require(row.getEnterpriseId(), row.getConnectionId());
        if (!value.getRevision().equals(row.getConnectionRevision()) || !value.getCredentialRevision().equals(row.getCredentialRevision())) {
            throw new ChannelProviderException("FEISHU_CONNECTION_CHANGED", Messages.text("error.integration.feishuSetupProcessor.connection.1"));
        }
        if (!List.of("draft", "enabled").contains(value.getStatus())) {
            throw new ChannelProviderException("FEISHU_CONNECTION_DISABLED", Messages.text("error.integration.feishuSetupProcessor.connection.2"));
        }
        return value;
    }

    private String appToken(FeishuSetupJobRow row, EnterpriseIntegrationRow connection) {
        return client.token(row.getId(), connection.getExternalAppId(), credentials.load(row.getEnterpriseId(), connection.getId()));
    }

    private String mutationToken(FeishuSetupJobRow row, EnterpriseIntegrationRow connection) {
        return row.getKind().equals("bot") ? managementTokens.get(store.manager(row.getEnterpriseId(), row.getManagerConnectionId()), row.getOwnerUserId())
            : appToken(row, connection);
    }

    static String nextVersion(String version) {
        if (version == null || !version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
            throw new ChannelProviderException("FEISHU_VERSION_INVALID", Messages.text("error.integration.feishuSetupProcessor.nextVersion.1"));
        }
        String[] parts = version.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }
}
