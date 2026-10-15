package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.model.collaboration.response.CollaborationReplyModeView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.chat.ChatReplyPreferenceService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import com.stonewu.agenteam.service.chat.ChatReplyCardUpdateService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/** 独立设置只改变回复方式，不创建输入、执行或模型调用。 */
@Service
public class CollaborationReplyModeService {
    private final CollaborationAccessService access;
    private final CollaborationTaskMapper tasks;
    private final EnterpriseMapper enterprises;
    private final ChatReplyPreferenceService preferences;
    private final AuditEventService audit;
    private final Clock clock;
    private final ObjectProvider<ChatReplyCardUpdateService> cards;

    public CollaborationReplyModeService(CollaborationAccessService access, CollaborationTaskMapper tasks,
            EnterpriseMapper enterprises, ChatReplyPreferenceService preferences, AuditEventService audit, Clock clock,
            ObjectProvider<ChatReplyCardUpdateService> cards) {
        this.access = access;
        this.tasks = tasks;
        this.enterprises = enterprises;
        this.preferences = preferences;
        this.audit = audit;
        this.clock = clock;
        this.cards = cards;
    }

    public CollaborationReplyModeView get(AuthContext actor, String id) {
        var task = access.owned(actor, id);
        var preference = preferences.get(actor, task.getConversationId());
        return new CollaborationReplyModeView(id, revision(task), preference.fullAllowed() ? task.getOutputVisibility() : "summary_link",
            preference, "completed".equals(task.getStatus()) && task.getFinalArtifactId() != null);
    }

    @Transactional
    public CollaborationReplyModeView save(AuthContext actor, String id, CollaborationReplyModeRequest input) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var task = access.owned(actor, id);
        var group = preferences.owned(actor, task.getConversationId());
        ChatReplyPreferenceService.validate(group, input.outputVisibility());
        long current = revision(task);
        if (input.expectedRevision() == null || input.expectedRevision() != current) {
            throw ApiException.versionConflict(current);
        }
        if (input.saveAsDefault()) {
            preferences.save(actor, group.getId(), input.outputVisibility(), input.expectedDefaultRevision());
        }
        if (!input.outputVisibility().equals(task.getOutputVisibility()) || !Boolean.TRUE.equals(task.getReplyModeExplicit())) {
            if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>()
                    .eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationTaskRow::getId, id)
                    .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId()).eq(CollaborationTaskRow::getReplyModeRevision, current)
                    .set(CollaborationTaskRow::getOutputVisibility, input.outputVisibility()).set(CollaborationTaskRow::getReplyModeExplicit, true)
                    .setIncrBy(CollaborationTaskRow::getReplyModeRevision, 1).set(CollaborationTaskRow::getUpdatedAt, clock.instant())) != 1) {
                throw ApiException.versionConflict(current);
            }
            audit.record(actor.enterpriseId(), actor.user(), "collaboration.reply_mode", "collaboration_task", id,
                "修改本人任务回复方式", Map.of("outputVisibility", input.outputVisibility()));
        }
        cards.getObject().changed(task);
        return get(actor, id);
    }

    public static long revision(CollaborationTaskRow task) {
        return task.getReplyModeRevision() == null ? 0 : task.getReplyModeRevision();
    }
}
