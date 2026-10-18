package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.ConversationTitleInput;
import com.stonewu.agenteam.model.execution.entity.ConversationTitleJob;
import com.stonewu.agenteam.model.execution.entity.ConversationTitleRequested;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** 任务与消息共同保存，模型网络调用由工作类在事务外执行。 */
@Service
public class ConversationTitleService {
    private final BackgroundJobMapper jobs;
    private final ConversationSqlMapper conversations;
    private final ExecutionMessageMapper messages;
    private final RunMapper runs;
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final EnterpriseAuthorizationService authorization;
    private final ModelProfileCatalog models;
    private final ResourceJson json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConversationTitleService(BackgroundJobMapper jobs, ConversationSqlMapper conversations,
                                    ExecutionMessageMapper messages, RunMapper runs, AuthMapper users,
                                    EnterpriseMapper enterprises, EnterpriseAuthorizationService authorization, ModelProfileCatalog models,
                                    ResourceJson json, ApplicationEventPublisher events, Clock clock) {
        this.jobs = jobs;
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.users = users;
        this.enterprises = enterprises;
        this.authorization = authorization;
        this.models = models;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void enqueue(AuthContext actor, RunAccepted accepted, ModelSelection model) {
        // 流程型员工没有单一的对话模型，保留初始标题。
        if (model == null) {
            return;
        }
        var payload = new ConversationTitleJob(accepted.conversationId(), accepted.inputMessageId(),
            accepted.runId(), model.modelProfileId());
        jobs.enqueue(UUID.randomUUID().toString(), actor.enterpriseId(), actor.userId(), ConversationTitleJob.KIND,
            ConversationTitleJob.key(accepted.conversationId()), json.write(json.tree(payload)), clock.instant());
        events.publishEvent(new ConversationTitleRequested(accepted.conversationId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ConversationTitleInput> prepare(JobLease lease) {
        requireActor(lease);
        if (!jobs.lockOwned(lease, clock.instant())) {
            return Optional.empty();
        }
        if (lease.exhausted()) {
            finish(lease, "failed", "TITLE_ATTEMPTS_EXHAUSTED", "标题生成已达到尝试次数上限。", 0);
            return Optional.empty();
        }
        var payload = payload(lease);
        var rows = conversations.findConversation(lease.enterpriseId(), lease.ownerUserId(), payload.conversationId(), true);
        if (rows.isEmpty() || !"normal".equals(rows.getFirst().getMode())
            || !"web".equals(rows.getFirst().getSourceType()) || "deleted".equals(rows.getFirst().getStatus())
            || !Integer.valueOf(0).equals(rows.getFirst().getTitleCustomized())) {
            finish(lease, "cancelled", null, null, 0);
            return Optional.empty();
        }
        var run = runs.find(lease.enterpriseId(), payload.runId(), false)
            .filter(value -> value.userId().equals(lease.ownerUserId())
                && value.conversationId().equals(payload.conversationId())
                && value.inputMessageId().equals(payload.inputMessageId())).orElseThrow(ConversationTitleService::unavailable);
        var selected = ModelSelection.fromConfig(run.executionConfig().path("config"));
        if (selected == null || !selected.modelProfileId().equals(payload.modelProfileId())) {
            throw unavailable();
        }
        models.requireAvailable(lease.enterpriseId(), payload.modelProfileId());
        var message = messages.find(lease.enterpriseId(), payload.conversationId(), payload.inputMessageId())
            .filter(value -> "user".equals(value.role()) && payload.runId().equals(value.runId()))
            .orElseThrow(ConversationTitleService::unavailable);
        var text = new StringBuilder(message.content());
        for (var attachment : message.attachments()) {
            if (attachment.get("name") instanceof String name) {
                text.append('\n').append(name);
            }
        }
        if (text.toString().isBlank()) {
            finish(lease, "completed", null, null, 0);
            return Optional.empty();
        }
        String content = text.toString();
        content = content.substring(0, content.offsetByCodePoints(0, Math.min(3000, content.codePointCount(0, content.length()))));
        return Optional.of(new ConversationTitleInput(payload.modelProfileId(), selected.reasoningEffort(), content,
            ModelCallContext.forTitle(lease, payload.conversationId(), rows.getFirst().getAgentId())));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(JobLease lease, String title) {
        requireActor(lease);
        if (!jobs.lockOwned(lease, clock.instant())) {
            return;
        }
        var payload = payload(lease);
        models.requireAvailable(lease.enterpriseId(), payload.modelProfileId());
        var rows = conversations.findConversation(lease.enterpriseId(), lease.ownerUserId(), payload.conversationId(), true);
        if (rows.isEmpty() || "deleted".equals(rows.getFirst().getStatus())
            || !Integer.valueOf(0).equals(rows.getFirst().getTitleCustomized())) {
            finish(lease, "cancelled", null, null, 0);
            return;
        }
        if (title != null && conversations.saveAutomaticTitle(lease.enterpriseId(), lease.ownerUserId(),
            payload.conversationId(), rows.getFirst().getRevision(), title) != 1) {
            throw new IllegalStateException("对话标题保存条件已变化，结果未提交");
        }
        finish(lease, "completed", null, null, 0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failed(JobLease lease, boolean retry) {
        if (!ConversationTitleJob.KIND.equals(lease.kind()) || !jobs.lockOwned(lease, clock.instant())) {
            return;
        }
        boolean again = retry && !lease.exhausted() && lease.attemptCount() < lease.maxAttempts();
        finish(lease, again ? "queued" : "failed", "TITLE_GENERATION_FAILED", "自动标题未能生成，保留原有标题。",
            again ? (lease.attemptCount() == 1 ? 30 : 120) : 0);
    }

    private void finish(JobLease lease, String status, String code, String summary, int delay) {
        var now = clock.instant();
        if (!jobs.finish(lease, status, code, summary, now.plusSeconds(delay), now)) {
            throw new IllegalStateException("标题任务执行权已变化，结果未提交");
        }
    }

    private void requireActor(JobLease lease) {
        if (!ConversationTitleJob.KIND.equals(lease.kind())) {
            throw unavailable();
        }
        // 与企业请求和账号清理保持相同顺序，避免账号锁与企业锁相互等待。
        enterprises.lockEnterprise(lease.enterpriseId()).filter("active"::equals)
            .orElseThrow(ConversationTitleService::unavailable);
        var user = users.findByIdForUpdate(lease.ownerUserId(), 2).orElseThrow(ConversationTitleService::unavailable);
        authorization.lockAndRequire(new AuthContext(user, lease.enterpriseId(), Set.of()), "agent.run");
    }

    private ConversationTitleJob payload(JobLease lease) {
        var value = json.read(lease.payloadJson());
        return new ConversationTitleJob(value.path("conversationId").asText(), value.path("inputMessageId").asText(),
            value.path("runId").asText(), value.path("modelProfileId").asText());
    }

    private static CancellationException unavailable() {
        return new CancellationException("标题任务的归属或权限已不可用。");
    }
}
