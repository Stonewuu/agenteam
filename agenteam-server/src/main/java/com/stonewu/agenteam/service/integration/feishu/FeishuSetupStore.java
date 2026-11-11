package com.stonewu.agenteam.service.integration.feishu;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.FeishuSetupJobMapper;
import com.stonewu.agenteam.model.chat.request.ChatBotWriteRequest;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.FeishuSetupJobRow;
import com.stonewu.agenteam.model.integration.request.FeishuSetupRequest;
import com.stonewu.agenteam.model.integration.response.FeishuSetupView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 创建进度每步独立保存，外部网络调用不占用数据库事务。 */
@Service
public class FeishuSetupStore {
    public static final Set<String> TERMINAL = Set.of("completed", "cancelled", "expired", "failed", "registration_unknown");
    private static final List<String> WORK = List.of("queued", "awaiting_scan", "preparing", "configuring", "publishing", "waiting_approval", "binding");
    private final FeishuSetupJobMapper jobs;
    private final AuthMapper users;
    private final EnterpriseIntegrationMapper connections;
    private final IntegrationQueryService queries;
    private final IntegrationManagementPolicy policy;
    private final ChatBotBindingService bots;
    private final FeishuSetupCipher cipher;
    private final ObjectMapper json;
    private final AuditEventService audit;
    private final Clock clock;
    private final FeishuManagementConnections managementConnections;

    public FeishuSetupStore(FeishuSetupJobMapper jobs, AuthMapper users, EnterpriseIntegrationMapper connections,
            IntegrationQueryService queries, IntegrationManagementPolicy policy, ChatBotBindingService bots,
            FeishuSetupCipher cipher, ObjectMapper json, AuditEventService audit, Clock clock, FeishuManagementConnections managementConnections) {
        this.jobs = jobs;
        this.users = users;
        this.connections = connections;
        this.queries = queries;
        this.policy = policy;
        this.bots = bots;
        this.cipher = cipher;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.managementConnections = managementConnections;
    }

    @Transactional
    public FeishuSetupView create(UserEntity actor, String enterprise, boolean system, FeishuSetupRequest input) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        policy.require(actor, enterprise, system, "integration.test", false);
        if (input.kind().equals("bot")) {
            manager(enterprise, input.managerConnectionId());
            bots.resolveAgentVersion(enterprise, binding(input, null, false));
        }
        var active = jobs.selectOne(new LambdaQueryWrapper<FeishuSetupJobRow>().eq(FeishuSetupJobRow::getEnterpriseId, enterprise)
            .eq(FeishuSetupJobRow::getOwnerUserId, actor.id()).eq(FeishuSetupJobRow::getKind, input.kind())
            .notIn(FeishuSetupJobRow::getStage, TERMINAL));
        if (active != null) {
            throw new ApiException(HttpStatus.CONFLICT, "FEISHU_SETUP_IN_PROGRESS", Messages.text("error.integration.feishuSetupStore.create.1"));
        }
        var row = new FeishuSetupJobRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setOwnerUserId(actor.id());
        row.setOwnerSessionVersion(actor.sessionVersion());
        row.setSystemAccess(system);
        row.setKind(input.kind());
        row.setManagerConnectionId(input.kind().equals("bot") ? input.managerConnectionId() : null);
        row.setInputJson(write(input));
        row.setStage("queued");
        if (input.connectionId() != null) {
            if (!"enterprise".equals(input.kind())) {
                throw ApiException.invalidField("connectionId", Messages.text("error.integration.feishuSetupStore.create.2"));
            }
            var source = queries.require(enterprise, input.connectionId());
            if (!"feishu".equals(source.getProviderCode()) || !Set.of("enterprise", "app_management").contains(source.getPurpose())
                || !Set.of("draft", "enabled").contains(source.getStatus())) {
                throw ApiException.invalidField("connectionId", Messages.text("error.integration.feishuSetupStore.create.3"));
            }
            if (jobs.exists(new LambdaQueryWrapper<FeishuSetupJobRow>().eq(FeishuSetupJobRow::getEnterpriseId, enterprise)
                    .eq(FeishuSetupJobRow::getConnectionId, source.getId()).notIn(FeishuSetupJobRow::getStage, TERMINAL))) {
                throw new ApiException(HttpStatus.CONFLICT, "FEISHU_SETUP_IN_PROGRESS", Messages.text("error.integration.feishuSetupStore.create.4"));
            }
            row.setConnectionId(source.getId());
            row.setConnectionRevision(source.getRevision());
            row.setCredentialRevision(source.getCredentialRevision());
            row.setStage("preparing");
        }
        row.setPollInterval(5);
        row.setNextAttemptAt(clock.instant());
        row.setRevision(1L);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        jobs.insert(row);
        audit.record(enterprise, actor, "feishu.setup_create", "feishu_setup", row.getId(), Messages.text("text.integration.feishuSetupStore.create.1"),
            Map.of("kind", row.getKind()));
        return view(row);
    }

    public List<FeishuSetupView> list(String enterprise, String user) {
        return jobs.selectPage(new Page<FeishuSetupJobRow>(1, 20, false), new LambdaQueryWrapper<FeishuSetupJobRow>()
            .eq(FeishuSetupJobRow::getEnterpriseId, enterprise).eq(FeishuSetupJobRow::getOwnerUserId, user)
            .orderByDesc(FeishuSetupJobRow::getCreatedAt, FeishuSetupJobRow::getId)).getRecords().stream().map(this::view).toList();
    }

    public FeishuSetupJobRow require(String enterprise, String user, String id) {
        var row = jobs.selectOne(new LambdaQueryWrapper<FeishuSetupJobRow>().eq(FeishuSetupJobRow::getId, id)
            .eq(FeishuSetupJobRow::getEnterpriseId, enterprise).eq(FeishuSetupJobRow::getOwnerUserId, user));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FEISHU_SETUP_UNAVAILABLE", Messages.text("error.integration.feishuSetupStore.require.1"));
        }
        return row;
    }

    public EnterpriseIntegrationRow manager(String enterprise, String id) {
        var row = queries.require(enterprise, id);
        if (!"feishu".equals(row.getProviderCode()) || !("app_management".equals(row.getPurpose())
                || "enterprise".equals(row.getPurpose()) && managementConnections.ready(row))
            || !"enabled".equals(row.getStatus()) || row.getIdentityVerifiedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "FEISHU_MANAGER_REQUIRED", Messages.text("error.integration.feishuSetupStore.manager.1"));
        }
        return row;
    }

    public List<EnterpriseIntegrationRow> managers(String enterprise) {
        return connections.selectPage(new Page<EnterpriseIntegrationRow>(1, 50, false),
            new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
                .eq(EnterpriseIntegrationRow::getProviderCode, "feishu").in(EnterpriseIntegrationRow::getPurpose, "enterprise", "app_management")
                .in(EnterpriseIntegrationRow::getStatus, "draft", "enabled")
                .orderByDesc(EnterpriseIntegrationRow::getCreatedAt)).getRecords();
    }

    public UserEntity actor(FeishuSetupJobRow row) {
        var actor = users.findById(row.getOwnerUserId()).filter(value -> value.status().equals("active")
            && value.sessionVersion() == row.getOwnerSessionVersion()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", Messages.text("error.integration.feishuSetupStore.actor.1")));
        policy.require(actor, row.getEnterpriseId(), row.getSystemAccess(), "integration.manage", false);
        return actor;
    }

    public List<FeishuSetupJobRow> due() {
        return jobs.selectPage(new Page<FeishuSetupJobRow>(1, 16, false), new LambdaQueryWrapper<FeishuSetupJobRow>()
            .in(FeishuSetupJobRow::getStage, WORK).le(FeishuSetupJobRow::getNextAttemptAt, clock.instant())
            .and(group -> group.isNull(FeishuSetupJobRow::getLeaseUntil).or().le(FeishuSetupJobRow::getLeaseUntil, clock.instant()))
            .orderByAsc(FeishuSetupJobRow::getNextAttemptAt)).getRecords();
    }

    @Transactional
    public FeishuSetupJobRow claim(FeishuSetupJobRow row, String owner) {
        int changed = jobs.update(new LambdaUpdateWrapper<FeishuSetupJobRow>().eq(FeishuSetupJobRow::getId, row.getId())
            .eq(FeishuSetupJobRow::getRevision, row.getRevision()).in(FeishuSetupJobRow::getStage, WORK)
            .and(group -> group.isNull(FeishuSetupJobRow::getLeaseUntil).or().le(FeishuSetupJobRow::getLeaseUntil, clock.instant()))
            .set(FeishuSetupJobRow::getLeaseOwner, owner).set(FeishuSetupJobRow::getLeaseUntil, clock.instant().plusSeconds(300))
            .set(FeishuSetupJobRow::getRevision, row.getRevision() + 1));
        return changed == 1 ? jobs.selectById(row.getId()) : null;
    }

    @Transactional
    public void save(FeishuSetupJobRow row) {
        var update = new LambdaUpdateWrapper<FeishuSetupJobRow>().eq(FeishuSetupJobRow::getId, row.getId())
            .eq(FeishuSetupJobRow::getEnterpriseId, row.getEnterpriseId()).eq(FeishuSetupJobRow::getRevision, row.getRevision())
            .eq(row.getLeaseOwner() != null, FeishuSetupJobRow::getLeaseOwner, row.getLeaseOwner())
            .set(FeishuSetupJobRow::getStage, row.getStage()).set(FeishuSetupJobRow::getResumeStage, row.getResumeStage())
            .set(FeishuSetupJobRow::getInputJson, row.getInputJson())
            .set(FeishuSetupJobRow::getEncryptedJson, row.getEncryptedJson())
            .set(FeishuSetupJobRow::getRegistrationExpiresAt, row.getRegistrationExpiresAt())
            .set(FeishuSetupJobRow::getPollInterval, row.getPollInterval()).set(FeishuSetupJobRow::getNextAttemptAt, row.getNextAttemptAt())
            .set(FeishuSetupJobRow::getConnectionId, row.getConnectionId()).set(FeishuSetupJobRow::getBotId, row.getBotId())
            .set(FeishuSetupJobRow::getConnectionRevision, row.getConnectionRevision()).set(FeishuSetupJobRow::getCredentialRevision, row.getCredentialRevision())
            .set(FeishuSetupJobRow::getExternalVersionId, row.getExternalVersionId()).set(FeishuSetupJobRow::getExternalVersion, row.getExternalVersion())
            .set(FeishuSetupJobRow::getErrorCode, row.getErrorCode()).set(FeishuSetupJobRow::getErrorSummary, row.getErrorSummary())
            .set(FeishuSetupJobRow::getOwnerSessionVersion, row.getOwnerSessionVersion())
            .set(FeishuSetupJobRow::getLeaseOwner, null).set(FeishuSetupJobRow::getLeaseUntil, null)
            .set(FeishuSetupJobRow::getUpdatedAt, clock.instant()).set(FeishuSetupJobRow::getRevision, row.getRevision() + 1);
        if (jobs.update(update) != 1) {
            throw ApiException.versionConflict(row.getRevision());
        }
        row.setRevision(row.getRevision() + 1);
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
        row.setUpdatedAt(clock.instant());
    }

    public FeishuSetupJobRow copy(FeishuSetupJobRow row) {
        var copy = new FeishuSetupJobRow();
        BeanUtils.copyProperties(row, copy);
        return copy;
    }

    /** 保存结果不明确时先核对领取记录，不能覆盖已提交或已被其他线程接管的进度。 */
    public boolean restoreClaim(FeishuSetupJobRow row, FeishuSetupJobRow claimed) {
        var current = jobs.selectById(row.getId());
        if (current == null || !claimed.getRevision().equals(current.getRevision())
                || !claimed.getLeaseOwner().equals(current.getLeaseOwner())) {
            return false;
        }
        row.setRevision(claimed.getRevision());
        row.setLeaseOwner(claimed.getLeaseOwner());
        row.setLeaseUntil(claimed.getLeaseUntil());
        return true;
    }

    @Transactional
    public FeishuSetupView change(UserEntity actor, String enterprise, boolean system, String id, long revision, boolean retry) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var row = require(enterprise, actor.id(), id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        if (row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "FEISHU_SETUP_BUSY", Messages.text("error.integration.feishuSetupStore.change.1"));
        }
        if (retry) {
            if (!Set.of("failed", "authorization_required").contains(row.getStage()) || row.getResumeStage() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "FEISHU_SETUP_RETRY_UNAVAILABLE", Messages.text("error.integration.feishuSetupStore.change.2"));
            }
            if (jobs.exists(new LambdaQueryWrapper<FeishuSetupJobRow>().eq(FeishuSetupJobRow::getEnterpriseId, enterprise)
                    .eq(FeishuSetupJobRow::getOwnerUserId, actor.id()).eq(FeishuSetupJobRow::getKind, row.getKind())
                    .ne(FeishuSetupJobRow::getId, row.getId()).notIn(FeishuSetupJobRow::getStage, TERMINAL))) {
                throw new ApiException(HttpStatus.CONFLICT, "FEISHU_SETUP_IN_PROGRESS", Messages.text("error.integration.feishuSetupStore.change.3"));
            }
            row.setStage(row.getResumeStage());
            row.setOwnerSessionVersion(actor.sessionVersion());
            row.setErrorCode(null);
            row.setErrorSummary(null);
            row.setNextAttemptAt(clock.instant());
        } else {
            if (row.getStage().equals("completed")) {
                throw new ApiException(HttpStatus.CONFLICT, "FEISHU_SETUP_COMPLETED", Messages.text("error.integration.feishuSetupStore.change.4"));
            }
            row.setStage("cancelled");
            row.setEncryptedJson(null);
        }
        save(row);
        audit.record(enterprise, actor, retry ? "feishu.setup_retry" : "feishu.setup_cancel", "feishu_setup", id,
            retry ? Messages.text("text.integration.feishuSetupStore.change.1") : Messages.text("text.integration.feishuSetupStore.change.2"), Map.of());
        return view(row);
    }

    public FeishuSetupRequest input(FeishuSetupJobRow row) {
        try {
            return json.readValue(row.getInputJson(), FeishuSetupRequest.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(Messages.text("error.integration.feishuSetupStore.input.1"), failure);
        }
    }

    /** 接入名称、岗位名称和创建进度共同采用用户在飞书最终确认的名称。 */
    public void applyConfirmedName(FeishuSetupJobRow row, String name) {
        var previous = input(row);
        String confirmed = ResourceInput.text(name, "name", 80, true);
        row.setInputJson(write(new FeishuSetupRequest(previous.kind(), previous.managerConnectionId(), previous.agentId(),
            previous.versionPolicy(), previous.pinnedVersionId(), confirmed, previous.description(), previous.connectionId(), previous.loginEnabled(),
            previous.messagingEnabled(), previous.bindingEnabled())));
    }

    public FeishuSetupCipher.Registration material(FeishuSetupJobRow row) {
        return cipher.decrypt(jobBinding(row), row.getEncryptedJson(), FeishuSetupCipher.Registration.class);
    }

    public void material(FeishuSetupJobRow row, FeishuSetupCipher.Registration material) {
        row.setEncryptedJson(cipher.encrypt(jobBinding(row), material));
    }

    private String jobBinding(FeishuSetupJobRow row) {
        return "job:" + row.getEnterpriseId() + ":" + row.getOwnerUserId() + ":" + row.getId();
    }

    public FeishuSetupView view(FeishuSetupJobRow row) {
        boolean free = row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(clock.instant());
        boolean canOpen = row.getStage().equals("awaiting_scan") && row.getRegistrationExpiresAt() != null
            && row.getRegistrationExpiresAt().isAfter(clock.instant());
        String message = row.getErrorSummary() != null ? row.getErrorSummary() : switch (row.getStage()) {
            case "queued" -> Messages.text("text.integration.feishuSetupStore.view.1");
            case "awaiting_scan" -> Messages.text("text.integration.feishuSetupStore.view.2");
            case "preparing" -> Messages.text("text.integration.feishuSetupStore.view.3");
            case "configuring" -> Messages.text("text.integration.feishuSetupStore.view.4");
            case "publishing" -> Messages.text("text.integration.feishuSetupStore.view.5");
            case "waiting_approval" -> Messages.text("text.integration.feishuSetupStore.view.6");
            case "binding" -> Messages.text("text.integration.feishuSetupStore.view.7");
            case "completed" -> row.getKind().equals("bot") ? Messages.text("text.integration.feishuSetupStore.view.8") : Messages.text("text.integration.feishuSetupStore.view.9");
            case "authorization_required" -> Messages.text("text.integration.feishuSetupStore.view.10");
            case "expired" -> Messages.text("text.integration.feishuSetupStore.view.11");
            case "registration_unknown" -> Messages.text("text.integration.feishuSetupStore.view.12");
            case "cancelled" -> Messages.text("text.integration.feishuSetupStore.view.13");
            default -> Messages.text("text.integration.feishuSetupStore.view.14");
        };
        return new FeishuSetupView(row.getId(), row.getKind(), input(row).name(), row.getStage(), message,
            row.getManagerConnectionId(), row.getConnectionId(), row.getBotId(), canOpen,
            free && Set.of("failed", "authorization_required").contains(row.getStage()) && row.getResumeStage() != null,
            free && !Set.of("completed", "cancelled", "expired").contains(row.getStage()), row.getRegistrationExpiresAt(), row.getRevision(), row.getUpdatedAt());
    }

    public static ChatBotWriteRequest binding(FeishuSetupRequest input, String connection, boolean enabled) {
        return new ChatBotWriteRequest(null, connection, input.agentId(), input.versionPolicy() == null ? "latest_on_new_task" : input.versionPolicy(),
            input.pinnedVersionId(), input.name(), input.description(), "websocket", null, enabled ? "enabled" : null, enabled);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(Messages.text("error.integration.feishuSetupStore.write.1"), failure);
        }
    }
}
