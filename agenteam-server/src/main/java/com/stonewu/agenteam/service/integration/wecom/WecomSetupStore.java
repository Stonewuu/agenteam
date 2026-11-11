package com.stonewu.agenteam.service.integration.wecom;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.integration.WecomSetupJobMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.request.ChatBotWriteRequest;
import com.stonewu.agenteam.model.integration.entity.WecomSetupJobRow;
import com.stonewu.agenteam.model.integration.request.WecomSetupRequest;
import com.stonewu.agenteam.model.integration.response.WecomSetupView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 创建进度独立保存，平台查询在事务外执行。 */
@Service
public class WecomSetupStore {
    private static final List<String> WORK = List.of("queued", "awaiting_scan", "verifying", "binding");
    private static final Set<String> TERMINAL = Set.of("completed", "cancelled", "expired");
    private final WecomSetupJobMapper jobs;
    private final WecomEnterpriseConnections enterprises;
    private final AuthMapper users;
    private final IntegrationManagementPolicy policy;
    private final ChatBotBindingService bots;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AuditEventService audit;

    public WecomSetupStore(WecomSetupJobMapper jobs, WecomEnterpriseConnections enterprises, AuthMapper users,
            IntegrationManagementPolicy policy, ChatBotBindingService bots, ResourceJson json, ObjectMapper mapper,
            Clock clock, AuditEventService audit) {
        this.jobs = jobs;
        this.enterprises = enterprises;
        this.users = users;
        this.policy = policy;
        this.bots = bots;
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    public WecomSetupView create(UserEntity actor, String enterprise, boolean system, WecomSetupRequest request) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        policy.require(actor, enterprise, system, "integration.test", false);
        var parent = enterprises.require(enterprise, request.enterpriseConnectionId());
        var input = new WecomSetupRequest(parent.getId(), request.agentId(),
            request.versionPolicy() == null ? "latest_on_new_task" : request.versionPolicy(), request.pinnedVersionId(),
            ResourceInput.text(request.name(), "name", 80, true), ResourceInput.text(request.description() == null ? "" : request.description(), "description", 500, false));
        bots.resolveAgentVersion(enterprise, binding(input, null, false));
        enterprises.requireAvailableName(enterprise, input.name());
        // 企业锁覆盖未完成流程的名称检查，避免两位管理员同时创建同名机器人。
        if (jobs.selectList(new LambdaQueryWrapper<WecomSetupJobRow>().eq(WecomSetupJobRow::getEnterpriseId, enterprise)
            .notIn(WecomSetupJobRow::getStage, TERMINAL)).stream()
            .anyMatch(existing -> input(existing).name().equalsIgnoreCase(input.name()))) {
            throw ApiException.invalidField("name", Messages.text("error.integration.wecomSetupStore.create.1"));
        }
        if (jobs.exists(new LambdaQueryWrapper<WecomSetupJobRow>().eq(WecomSetupJobRow::getEnterpriseId, enterprise)
            .eq(WecomSetupJobRow::getOwnerUserId, actor.id()).notIn(WecomSetupJobRow::getStage, TERMINAL))) {
            throw new ApiException(HttpStatus.CONFLICT, "WECOM_SETUP_IN_PROGRESS", Messages.text("error.integration.wecomSetupStore.create.2"));
        }
        var row = new WecomSetupJobRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setOwnerUserId(actor.id());
        row.setOwnerSessionVersion(actor.sessionVersion());
        row.setSystemAccess(system);
        row.setEnterpriseConnectionId(parent.getId());
        row.setEnterpriseCredentialRevision(parent.getCredentialRevision());
        row.setInputJson(json.write(json.tree(input)));
        row.setStage("queued");
        row.setNextAttemptAt(clock.instant());
        row.setRevision(1L);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        jobs.insert(row);
        audit.record(enterprise, actor, "wecom.setup_create", "wecom_setup", row.getId(), Messages.text("text.integration.wecomSetupStore.create.1"), Map.of());
        return view(row);
    }

    public List<WecomSetupView> list(String enterprise, String user) {
        return jobs.selectPage(new Page<WecomSetupJobRow>(1, 20, false), new LambdaQueryWrapper<WecomSetupJobRow>()
            .eq(WecomSetupJobRow::getEnterpriseId, enterprise).eq(WecomSetupJobRow::getOwnerUserId, user)
            .orderByDesc(WecomSetupJobRow::getCreatedAt, WecomSetupJobRow::getId)).getRecords().stream().map(this::view).toList();
    }

    public WecomSetupJobRow require(String enterprise, String user, String id) {
        var row = jobs.selectOne(new LambdaQueryWrapper<WecomSetupJobRow>().eq(WecomSetupJobRow::getEnterpriseId, enterprise)
            .eq(WecomSetupJobRow::getOwnerUserId, user).eq(WecomSetupJobRow::getId, id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WECOM_SETUP_UNAVAILABLE", Messages.text("error.integration.feishuSetupStore.require.1"));
        }
        return row;
    }

    public UserEntity actor(WecomSetupJobRow row) {
        var actor = users.findById(row.getOwnerUserId()).filter(value -> "active".equals(value.status())
            && value.sessionVersion() == row.getOwnerSessionVersion()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", Messages.text("error.integration.wecomSetupStore.actor.1")));
        policy.require(actor, row.getEnterpriseId(), row.getSystemAccess(), "integration.manage", false);
        return actor;
    }

    public List<WecomSetupJobRow> due() {
        return jobs.selectPage(new Page<WecomSetupJobRow>(1, 8, false), new LambdaQueryWrapper<WecomSetupJobRow>()
            .in(WecomSetupJobRow::getStage, WORK).le(WecomSetupJobRow::getNextAttemptAt, clock.instant())
            .and(group -> group.isNull(WecomSetupJobRow::getLeaseUntil).or().le(WecomSetupJobRow::getLeaseUntil, clock.instant()))
            .orderByAsc(WecomSetupJobRow::getNextAttemptAt)).getRecords();
    }

    @Transactional
    public WecomSetupJobRow claim(WecomSetupJobRow row, String owner) {
        int changed = jobs.update(new LambdaUpdateWrapper<WecomSetupJobRow>().eq(WecomSetupJobRow::getId, row.getId())
            .eq(WecomSetupJobRow::getRevision, row.getRevision()).in(WecomSetupJobRow::getStage, WORK)
            .and(group -> group.isNull(WecomSetupJobRow::getLeaseUntil).or().le(WecomSetupJobRow::getLeaseUntil, clock.instant()))
            .set(WecomSetupJobRow::getLeaseOwner, owner).set(WecomSetupJobRow::getLeaseUntil, clock.instant().plusSeconds(120))
            .set(WecomSetupJobRow::getRevision, row.getRevision() + 1));
        return changed == 1 ? jobs.selectById(row.getId()) : null;
    }

    @Transactional
    public void save(WecomSetupJobRow row) {
        var update = new LambdaUpdateWrapper<WecomSetupJobRow>().eq(WecomSetupJobRow::getId, row.getId())
            .eq(WecomSetupJobRow::getEnterpriseId, row.getEnterpriseId()).eq(WecomSetupJobRow::getRevision, row.getRevision())
            .eq(row.getLeaseOwner() != null, WecomSetupJobRow::getLeaseOwner, row.getLeaseOwner())
            .set(WecomSetupJobRow::getStage, row.getStage()).set(WecomSetupJobRow::getResumeStage, row.getResumeStage())
            .set(WecomSetupJobRow::getEncryptedJson, row.getEncryptedJson()).set(WecomSetupJobRow::getExpiresAt, row.getExpiresAt())
            .set(WecomSetupJobRow::getNextAttemptAt, row.getNextAttemptAt()).set(WecomSetupJobRow::getConnectionId, row.getConnectionId())
            .set(WecomSetupJobRow::getBotId, row.getBotId()).set(WecomSetupJobRow::getPlatformName, row.getPlatformName())
            .set(WecomSetupJobRow::getErrorCode, row.getErrorCode()).set(WecomSetupJobRow::getErrorSummary, row.getErrorSummary())
            .set(WecomSetupJobRow::getOwnerSessionVersion, row.getOwnerSessionVersion())
            .set(WecomSetupJobRow::getEnterpriseCredentialRevision, row.getEnterpriseCredentialRevision())
            .set(WecomSetupJobRow::getLeaseOwner, null).set(WecomSetupJobRow::getLeaseUntil, null)
            .set(WecomSetupJobRow::getUpdatedAt, clock.instant()).set(WecomSetupJobRow::getRevision, row.getRevision() + 1);
        if (jobs.update(update) != 1) {
            throw ApiException.versionConflict(row.getRevision());
        }
        row.setRevision(row.getRevision() + 1);
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
    }

    @Transactional
    public WecomSetupView change(UserEntity actor, String enterprise, boolean system, String id, long revision, boolean retry) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var row = require(enterprise, actor.id(), id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        if (row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "WECOM_SETUP_BUSY", Messages.text("error.integration.wecomSetupStore.change.1"));
        }
        if (retry) {
            if (!"failed".equals(row.getStage())) {
                throw ApiException.invalidField("stage", Messages.text("error.integration.wecomSetupStore.change.2"));
            }
            var parent = enterprises.require(enterprise, row.getEnterpriseConnectionId());
            row.setEnterpriseCredentialRevision(parent.getCredentialRevision());
            row.setOwnerSessionVersion(actor.sessionVersion());
            row.setStage(row.getResumeStage());
            row.setErrorCode(null);
            row.setErrorSummary(null);
            row.setNextAttemptAt(clock.instant());
        } else {
            if (TERMINAL.contains(row.getStage())) {
                throw ApiException.invalidField("stage", Messages.text("error.integration.wecomSetupStore.change.3"));
            }
            row.setStage("cancelled");
            row.setEncryptedJson(null);
        }
        save(row);
        audit.record(enterprise, actor, retry ? "wecom.setup_retry" : "wecom.setup_cancel", "wecom_setup", id,
            retry ? Messages.text("text.integration.wecomSetupStore.change.1") : Messages.text("text.integration.wecomSetupStore.change.2"), Map.of());
        return view(row);
    }

    public WecomSetupView view(WecomSetupJobRow row) {
        boolean open = "awaiting_scan".equals(row.getStage()) && row.getExpiresAt() != null && row.getExpiresAt().isAfter(clock.instant());
        return new WecomSetupView(row.getId(), input(row).name(), row.getPlatformName(), row.getStage(), row.getErrorSummary(),
            row.getEnterpriseConnectionId(), row.getConnectionId(), row.getBotId(), open, "failed".equals(row.getStage()),
            !TERMINAL.contains(row.getStage()), row.getExpiresAt(), row.getRevision(), row.getUpdatedAt());
    }

    public WecomSetupRequest input(WecomSetupJobRow row) {
        return mapper.convertValue(json.read(row.getInputJson()), WecomSetupRequest.class);
    }

    public WecomSetupJobRow copy(WecomSetupJobRow row) {
        var result = new WecomSetupJobRow();
        BeanUtils.copyProperties(row, result);
        return result;
    }

    public static ChatBotWriteRequest binding(WecomSetupRequest input, String connection, boolean enabled) {
        return new ChatBotWriteRequest(null, connection, input.agentId(), input.versionPolicy(), input.pinnedVersionId(),
            input.name(), input.description(), "websocket", null, enabled ? "enabled" : null, enabled);
    }
}
