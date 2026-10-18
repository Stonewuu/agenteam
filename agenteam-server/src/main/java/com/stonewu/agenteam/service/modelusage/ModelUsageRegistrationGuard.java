package com.stonewu.agenteam.service.modelusage;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ConversationTitleJob;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * 发送前与账号清理共用用户锁，避免已经取消或清理的旧执行登记新的请求。
 */
@Component
public class ModelUsageRegistrationGuard {
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final RunMapper runs;
    private final AccountBehaviorService behavior;
    private final BackgroundJobSqlMapper jobs;
    private final ConversationSqlMapper conversations;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceJson json;
    private final Clock clock;

    public ModelUsageRegistrationGuard(AuthMapper users, EnterpriseMapper enterprises, RunMapper runs, AccountBehaviorService behavior,
                                       BackgroundJobSqlMapper jobs, ConversationSqlMapper conversations,
                                       EnterpriseAuthorizationService authorization, ResourceJson json, Clock clock) {
        this.users = users;
        this.enterprises = enterprises;
        this.runs = runs;
        this.behavior = behavior;
        this.jobs = jobs;
        this.conversations = conversations;
        this.authorization = authorization;
        this.json = json;
        this.clock = clock;
    }

    public void requireActive(ModelCallContext context) {
        if (context.purpose().equals("title")) {
            // 标题还需锁企业校验权限，必须在账号更新锁之前取得企业锁。
            enterprises.lockEnterprise(context.enterpriseId()).filter("active"::equals)
                .orElseThrow(() -> new CancellationException("标题所属企业已不可用。"));
        }
        var user = users.findByIdForUpdate(context.userId(), 2).orElseThrow(() -> new CancellationException("模型调用账号已不可用。"));
        if (!user.status().equals("active") || !behavior.allowed(user)) {
            throw new CancellationException("模型调用账号已不可用。");
        }
        if (context.purpose().equals("title")) {
            authorization.lockAndRequire(new AuthContext(user, context.enterpriseId(), Set.of()), "agent.run");
            requireTitle(context);
            return;
        }
        if (context.backgroundJob() != null) {
            throw new CancellationException("模型调用的后台任务类型不正确。");
        }
        if (context.runId() == null && context.purpose().equals("preview")) {
            return;
        }
        var run = runs.find(context.enterpriseId(), context.runId(), false).orElseThrow(() -> new CancellationException("本次执行已不存在。"));
        if (!run.userId().equals(context.userId()) || !run.status().equals("running")
            || run.currentAttemptNo() != context.attemptNo() || run.leaseVersion() != context.leaseVersion()) {
            throw new CancellationException("本次执行已结束或已由另一次尝试继续。");
        }
    }

    private void requireTitle(ModelCallContext context) {
        var identity = context.backgroundJob();
        var job = identity == null ? null : jobs.selectById(identity.id());
        if (job == null || context.runId() != null || !ConversationTitleJob.KIND.equals(job.getKind())
            || !Objects.equals(context.enterpriseId(), job.getEnterpriseId())
            || !Objects.equals(context.userId(), job.getOwnerUserId()) || !"leased".equals(job.getStatus())
            || !Objects.equals(identity.owner(), job.getLeaseOwner()) || identity.version() != job.getLeaseVersion()
            || job.getLeaseUntil() == null || !job.getLeaseUntil().isAfter(clock.instant())
            || !Objects.equals(ConversationTitleJob.key(context.conversationId()), job.getDedupeKey())
            || !Objects.equals(context.conversationId(), json.read(job.getPayloadJson()).path("conversationId").asText())) {
            throw new CancellationException("标题模型调用的执行权或归属已失效。");
        }
        var conversation = conversations.selectById(context.conversationId());
        if (conversation == null || !Objects.equals(context.enterpriseId(), conversation.getEnterpriseId())
            || !Objects.equals(context.userId(), conversation.getUserId()) || "deleted".equals(conversation.getStatus())
            || !Integer.valueOf(0).equals(conversation.getTitleCustomized()) || !"normal".equals(conversation.getMode())
            || !"web".equals(conversation.getSourceType())) {
            throw new CancellationException("此对话已不再允许自动命名。");
        }
    }
}
