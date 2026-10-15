package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.request.ChatBotWriteRequest;
import com.stonewu.agenteam.model.chat.request.ChatWebhookSettingsRequest;
import com.stonewu.agenteam.model.chat.response.ChatBotView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 机器人身份在事务外校验，配置、版本检查和审计一起提交。 */
@Service
public class ChatBotBindingService {
    public record PreparedIdentity(String connectionId, long connectionRevision, ChatPlatformAdapter.BotIdentity identity) {
    }

    private final ChatBotBindingMapper bots;
    private final EnterpriseIntegrationMapper connections;
    private final IntegrationManagementPolicy policy;
    private final IntegrationQueryService integrations;
    private final ChatPlatformRegistry platforms;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceJson json;
    private final ListPagination pagination;
    private final AuditEventService audit;
    private final Clock clock;
    private final IntegrationSecretService secrets;
    private final ResourceSqlMapper resourceRows;

    public ChatBotBindingService(ChatBotBindingMapper bots, EnterpriseIntegrationMapper connections,
                                 IntegrationManagementPolicy policy, IntegrationQueryService integrations,
                                 ChatPlatformRegistry platforms, ResourceMapper resources, ResourceVersionMapper versions,
                                 ResourceJson json, ListPagination pagination, AuditEventService audit, Clock clock, IntegrationSecretService secrets,
                                 ResourceSqlMapper resourceRows) {
        this.bots = bots;
        this.connections = connections;
        this.policy = policy;
        this.integrations = integrations;
        this.platforms = platforms;
        this.resources = resources;
        this.versions = versions;
        this.json = json;
        this.pagination = pagination;
        this.audit = audit;
        this.clock = clock;
        this.secrets = secrets;
        this.resourceRows = resourceRows;
    }

    public PreparedIdentity prepare(UserEntity actor, String enterprise, boolean system, String connectionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("机器人身份校验不能占用数据库事务");
        }
        policy.require(actor, enterprise, system, "integration.manage", false);
        var connection = integrations.require(enterprise, connectionId);
        if (!Set.of("feishu", "wecom_aibot").contains(connection.getProviderCode())) {
            throw ApiException.invalidField("connectionId", "请选择飞书应用或企微智能机器人，普通企微自建应用不能用于群聊。");
        }
        var identity = platforms.adapter(connection.getProviderCode()).identity(integrations.application(connection));
        return new PreparedIdentity(connectionId, connection.getRevision(), identity);
    }

    @Transactional
    public ChatBotView create(UserEntity actor, String enterprise, boolean system, ChatBotWriteRequest input, PreparedIdentity prepared) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var connection = integrations.require(enterprise, input.connectionId());
        if (!connection.getId().equals(prepared.connectionId()) || connection.getRevision() != prepared.connectionRevision()) {
            throw ApiException.versionConflict(connection.getRevision());
        }
        String version = version(enterprise, input);
        var row = ChatRecords.initialize(new ChatBotBindingRow(), enterprise, clock.instant());
        row.setConnectionId(connection.getId());
        row.setPlatformBotId(prepared.identity().id());
        row.setAgentId(input.agentId());
        row.setVersionPolicy(input.versionPolicy());
        row.setPinnedVersionId(input.versionPolicy().equals("pinned") ? version : null);
        row.setRoleName(ResourceInput.text(input.roleName(), "roleName", 80, true));
        row.setRoleDescription(ResourceInput.text(input.roleDescription(), "roleDescription", 500, false));
        if (row.getRoleDescription() == null) {
            row.setRoleDescription("");
        }
        row.setTransport(transport(input.transport(), connection.getProviderCode()));
        row.setPublicCallbackKey(UUID.randomUUID().toString().replace("-", ""));
        row.setStatus("draft");
        row.setChatEnabled(false);
        var initialCapabilities = prepared.identity().capabilities().deepCopy();
        initialCapabilities.forEach(value -> ((ObjectNode) value).put("credentialRevision", connection.getCredentialRevision()));
        row.setCapabilitiesJson(json.write(initialCapabilities));
        row.setConfigJson("{}");
        row.setCheckedRevision(1L);
        row.setLeaseVersion(0L);
        try {
            bots.insert(row);
        } catch (DuplicateKeyException failure) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_BOT_ALREADY_BOUND", "此平台机器人已绑定岗位，请编辑已有绑定。", failure);
        }
        audit.record(enterprise, actor, "chat.bot_create", "chat_bot", row.getId(), "绑定岗位机器人", Map.of("connectionId", connection.getId(), "agentId", row.getAgentId()));
        return view(row, connection);
    }

    @Transactional
    public ChatBotView update(UserEntity actor, String enterprise, boolean system, String id, long revision, ChatBotWriteRequest input) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var row = require(enterprise, id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        String version = version(enterprise, input);
        String status = input.status() == null ? row.getStatus() : input.status();
        if (!Set.of("draft", "enabled", "disabled").contains(status)) {
            throw ApiException.invalidField("status", "请选择启用或停用机器人。");
        }
        var connection = integrations.require(enterprise, row.getConnectionId());
        if ("webhook".equals(input.transport()) && status.equals("enabled") && !callbackConfigured(row)) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CALLBACK_SECRET_REQUIRED", "请先保存飞书回调校验令牌与加密密钥。");
        }
        if (status.equals("enabled") && !connection.getStatus().equals("enabled")) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_CHECK_REQUIRED", "请先校验并启用应用接入。");
        }
        boolean enabled = status.equals("enabled") && Boolean.TRUE.equals(input.chatEnabled() == null ? row.getChatEnabled() : input.chatEnabled());
        if (input.connectionId() != null && !input.connectionId().equals(row.getConnectionId())) {
            throw ApiException.invalidField("connectionId", "绑定的平台身份不能直接更换，请新建绑定。");
        }
        int changed = bots.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise)
            .eq(ChatBotBindingRow::getId, id).eq(ChatBotBindingRow::getRevision, revision)
            .set(ChatBotBindingRow::getAgentId, input.agentId()).set(ChatBotBindingRow::getVersionPolicy, input.versionPolicy())
            .set(ChatBotBindingRow::getPinnedVersionId, input.versionPolicy().equals("pinned") ? version : null)
            .set(ChatBotBindingRow::getRoleName, ResourceInput.text(input.roleName(), "roleName", 80, true))
            .set(ChatBotBindingRow::getRoleDescription, input.roleDescription() == null ? "" : ResourceInput.text(input.roleDescription(), "roleDescription", 500, false))
            .set(ChatBotBindingRow::getTransport, transport(input.transport(), connection.getProviderCode())).set(ChatBotBindingRow::getStatus, status)
            .set(ChatBotBindingRow::getChatEnabled, enabled).set(ChatBotBindingRow::getRevision, revision + 1)
            .set(ChatBotBindingRow::getUpdatedAt, clock.instant()));
        if (changed != 1) {
            throw ApiException.versionConflict(revision);
        }
        audit.record(enterprise, actor, "chat.bot_update", "chat_bot", id, "更新岗位机器人", Map.of("status", status));
        return view(require(enterprise, id), connection);
    }

    public PageResponse<ChatBotView> list(UserEntity actor, String enterprise, boolean system, String cursor, Integer requested) {
        policy.require(actor, enterprise, system, "integration.view", false);
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.id(), enterprise, "chat/bots", "", "created_desc");
        var after = pagination.read(cursor, binding);
        var query = new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise)
            .ne(ChatBotBindingRow::getStatus, "deleted").orderByDesc(ChatBotBindingRow::getCreatedAt, ChatBotBindingRow::getId);
        if (after != null) {
            query.and(value -> value.lt(ChatBotBindingRow::getCreatedAt, after.time()).or(equal -> equal
                .eq(ChatBotBindingRow::getCreatedAt, after.time()).lt(ChatBotBindingRow::getId, after.id())));
        }
        var page = pagination.page(bots.selectPage(new Page<ChatBotBindingRow>(1, limit + 1, false), query).getRecords(),
            limit, binding, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        var ids = page.items().stream().map(ChatBotBindingRow::getConnectionId).toList();
        Map<String, EnterpriseIntegrationRow> apps = ids.isEmpty() ? Map.of() : connections.selectList(
            new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
                .in(EnterpriseIntegrationRow::getId, ids)).stream().collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        var agentIds = page.items().stream().map(ChatBotBindingRow::getAgentId).distinct().toList();
        Map<String, ResourceRow> agents = agentIds.isEmpty() ? Map.of() : resourceRows.selectList(new LambdaQueryWrapper<ResourceRow>()
            .select(ResourceRow::getId, ResourceRow::getName, ResourceRow::getPublishedVersionId).eq(ResourceRow::getEnterpriseId, enterprise)
            .in(ResourceRow::getId, agentIds)).stream().collect(Collectors.toMap(ResourceRow::getId, Function.identity()));
        var token = secrets.configured(enterprise, ids, "chat_verification_token");
        var encrypt = secrets.configured(enterprise, ids, "chat_encrypt_key");
        return new PageResponse<>(page.items().stream().map(row -> {
            var agent = agents.get(row.getAgentId());
            return view(row, apps.get(row.getConnectionId()), agent == null ? null : agent.getPublishedVersionId(),
                token.contains(row.getConnectionId()) && encrypt.contains(row.getConnectionId()), agent == null ? null : agent.getName());
        }).toList(), page.nextCursor(), page.hasMore());
    }

    public ChatBotBindingRow require(String enterprise, String id) {
        var row = bots.selectOne(new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise)
            .eq(ChatBotBindingRow::getId, id).ne(ChatBotBindingRow::getStatus, "deleted"));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_BOT_UNAVAILABLE", "机器人绑定不存在或已停用。");
        }
        return row;
    }

    @Transactional
    public ChatBotView callbackSettings(UserEntity actor, String enterprise, boolean system, String id, ChatWebhookSettingsRequest input) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var bot = require(enterprise, id);
        var app = integrations.require(enterprise, bot.getConnectionId());
        if (bot.getRevision() != input.expectedRevision()) {
            throw ApiException.versionConflict(bot.getRevision());
        }
        if (!app.getProviderCode().equals("feishu")) {
            throw ChatPlatformRegistry.unavailable();
        }
        secrets.store(enterprise, app.getId(), "chat_verification_token", input.verificationToken(), actor.id(), bot.getRevision() + 1, clock.instant());
        secrets.store(enterprise, app.getId(), "chat_encrypt_key", input.encryptKey(), actor.id(), bot.getRevision() + 1, clock.instant());
        if (bots.update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise)
            .eq(ChatBotBindingRow::getId, id).eq(ChatBotBindingRow::getRevision, bot.getRevision())
            .setIncrBy(ChatBotBindingRow::getRevision, 1).set(ChatBotBindingRow::getUpdatedAt, clock.instant())) != 1) {
            throw ApiException.versionConflict(bot.getRevision());
        }
        audit.record(enterprise, actor, "chat.callback_secrets", "chat_bot", id, "更新机器人回调认证材料", Map.of());
        return view(require(enterprise, id), app);
    }

    public IntegrationApplication application(ChatBotBindingRow bot) {
        var connection = integrations.require(bot.getEnterpriseId(), bot.getConnectionId());
        if (!connection.getStatus().equals("enabled")) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_DISABLED", "此机器人的应用接入已停用。");
        }
        return integrations.application(connection);
    }

    public boolean capability(ChatBotBindingRow bot, String name) {
        var connection = integrations.require(bot.getEnterpriseId(), bot.getConnectionId());
        return capability(bot, name, connection);
    }

    public boolean currentCredentials(ChatBotBindingRow bot, long revision) {
        var connection = connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, bot.getEnterpriseId()).eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()));
        return connection != null && connection.getStatus().equals("enabled") && connection.getCredentialRevision() == revision;
    }

    public Map<String, Boolean> capabilities(String enterprise, List<ChatBotBindingRow> rows, String name) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        var ids = rows.stream().map(ChatBotBindingRow::getConnectionId).distinct().toList();
        var apps = connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).in(EnterpriseIntegrationRow::getId, ids)).stream()
            .collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        return rows.stream().collect(Collectors.toMap(ChatBotBindingRow::getId, bot -> capability(bot, name, apps.get(bot.getConnectionId()))));
    }

    private boolean capability(ChatBotBindingRow bot, String name, EnterpriseIntegrationRow connection) {
        var value = json.read(bot.getCapabilitiesJson()).path(name);
        return connection != null && connection.getStatus().equals("enabled") && "passed".equals(value.path("status").asText())
            && value.path("credentialRevision").asLong(-1) == connection.getCredentialRevision();
    }

    private String version(String enterprise, ChatBotWriteRequest input) {
        if (!Set.of("pinned", "latest_on_new_task").contains(input.versionPolicy() == null ? "" : input.versionPolicy())) {
            throw ApiException.invalidField("versionPolicy", "请选择固定版本或新任务使用最新版本。");
        }
        var agent = resources.find(enterprise, input.agentId(), false, false)
            .filter(value -> value.kind() == ResourceKind.AGENT).orElseThrow(ChatPlatformRegistry::unavailable);
        String selected = input.versionPolicy().equals("pinned") ? input.pinnedVersionId() : agent.publishedVersionId();
        if (selected == null || versions.find(enterprise, selected).filter(value -> value.resourceId().equals(input.agentId())
            && value.status().equals("available")).isEmpty()) {
            throw ApiException.invalidField("pinnedVersionId", "请选择此岗位可用的已发布版本。");
        }
        return selected;
    }

    private String transport(String value, String provider) {
        if (!Set.of("websocket", "webhook").contains(value == null ? "" : value)) {
            throw ApiException.invalidField("transport", "请选择平台支持的接收方式。");
        }
        if (provider.equals("wecom_aibot") && !value.equals("websocket")) {
            throw ApiException.invalidField("transport", "企微智能机器人使用长连接接收消息。");
        }
        return value;
    }

    private ChatBotView view(ChatBotBindingRow row, EnterpriseIntegrationRow app) {
        var current = resources.find(row.getEnterpriseId(), row.getAgentId(), false, false).orElse(null);
        return view(row, app, current == null ? null : current.publishedVersionId(), callbackConfigured(row), current == null ? null : current.name());
    }

    private ChatBotView view(ChatBotBindingRow row, EnterpriseIntegrationRow app, String currentVersion, boolean callbackConfigured, String agentName) {
        return new ChatBotView(row.getId(), row.getConnectionId(), app.getProviderCode(), row.getPlatformBotId(),
            row.getAgentId(), row.getVersionPolicy().equals("pinned") ? row.getPinnedVersionId() : currentVersion, row.getVersionPolicy(), row.getRoleName(), row.getRoleDescription(),
            row.getTransport(), row.getStatus(), Boolean.TRUE.equals(row.getChatEnabled()), row.getRevision(),
            json.object(currentCapabilities(row, app)), time(row.getLastConnectedAt()), time(row.getLastMessageAt()),
            app.getProviderCode().equals("feishu") ? "/api/v1/chat/callbacks/feishu/" + row.getPublicCallbackKey() : null, callbackConfigured,
            json.read(row.getConfigJson()).path("connectionStoppedRevision").asLong(-1) == row.getRevision()
                ? json.read(row.getConfigJson()).path("connectionError").asText(null) : null, agentName);
    }

    private boolean callbackConfigured(ChatBotBindingRow row) {
        return secrets.configured(row.getEnterpriseId(), row.getConnectionId(), "chat_verification_token")
            && secrets.configured(row.getEnterpriseId(), row.getConnectionId(), "chat_encrypt_key");
    }

    private JsonNode currentCapabilities(ChatBotBindingRow bot, EnterpriseIntegrationRow app) {
        var result = json.read(bot.getCapabilitiesJson()).deepCopy();
        result.forEach(value -> {
            if (value.path("status").asText().equals("passed")
                && value.path("credentialRevision").asLong(-1) != app.getCredentialRevision()) {
                ((ObjectNode) value).put("status", "not_tested");
            }
        });
        return result;
    }

    private String time(Instant value) {
        return value == null ? null : value.toString();
    }
}
