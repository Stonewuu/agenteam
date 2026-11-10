package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunJobMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.QueuedMessageView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.service.usage.QuotaReservationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 调用方已锁定对话。等当前操作保存结果后，才允许下一条消息被后台领取。 */
@Service
public class ConversationRunQueue {
    private final RunMapper runs;
    private final ConversationMapper conversations;
    private final ExecutionMessageMapper messages;
    private final ExecutionEventMapper events;
    private final RunJobMapper jobs;
    private final QuotaReservationService quotas;
    private final MessageAttachmentMapper attachments;

    public ConversationRunQueue(RunMapper runs, ConversationMapper conversations, ExecutionMessageMapper messages,
                                ExecutionEventMapper events, RunJobMapper jobs, QuotaReservationService quotas,
                                MessageAttachmentMapper attachments) {
        this.attachments = attachments;
        this.runs = runs;
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
        this.jobs = jobs;
        this.quotas = quotas;
    }

    public static boolean supported(RunRecord run) {
        return "interactive".equals(run.mode()) && !"collaboration".equals(run.executionConfig().path("sourceType").asText());
    }

    public void afterFinish(RunRecord previous, Instant now) {
        if (!supported(previous)) {
            return;
        }
        var ids = runs.waitingIds(previous.enterpriseId(), previous.conversationId());
        for (String id : ids) {
            var next = runs.find(previous.enterpriseId(), id, true).orElseThrow();
            if (previous.status().equals("completed")) {
                var selected = ModelSelection.fromConfig(next.executionConfig().path("config"));
                if (selected != null && !selected.equals(ModelSelection.fromConfig(previous.executionConfig().path("config")))) {
                    var conversation = conversations.find(next.enterpriseId(), next.userId(), next.conversationId(), true).orElseThrow();
                    if (!selected.equals(conversation.modelSelection())) {
                        conversations.selectModel(conversation, selected, now);
                    }
                }
                runs.activateWaiting(next, previous.id(), now);
                conversations.activate(next.enterpriseId(), next.conversationId(), next.id(), now);
                var ready = runs.find(next.enterpriseId(), next.id(), false).orElseThrow();
                events.append(ready, "run.created", RunView.from(ready, false), now);
                messages.startWaitingPair(ready.enterpriseId(), ready.conversationId(), ready.id(), now);
                messages.status(ready.enterpriseId(), ready.inputMessageId(), "completed", ready.lastSequence(), now);
                events.append(ready, "queue.updated", QueuedMessageView.removed(ready.id()), now);
                for (String idToShow : List.of(ready.inputMessageId(), ready.outputMessageId())) {
                    events.append(ready, "message.created",
                        messages.find(ready.enterpriseId(), ready.conversationId(), idToShow).orElseThrow(), now);
                }
                jobs.enqueue(next.enterpriseId(), next.userId(), next.id(), now);
                return;
            }
            cancel(next, now, previous.status().equals("failed"));
        }
    }

    public void cancel(RunRecord waiting, Instant now) {
        cancel(waiting, now, false);
    }

    private void cancel(RunRecord waiting, Instant now, boolean preserveInput) {
        runs.finish(waiting, "cancelled", preserveInput ? "PRECEDING_RUN_FAILED" : null, null, now);
        quotas.release(waiting.enterpriseId(), waiting.id());
        messages.status(waiting.enterpriseId(), waiting.inputMessageId(), "cancelled", waiting.lastSequence(), now);
        messages.status(waiting.enterpriseId(), waiting.outputMessageId(), "cancelled", waiting.lastSequence(), now);
        events.append(waiting, "queue.updated", QueuedMessageView.removed(waiting.id()), now);
        if (!preserveInput) {
            attachments.removeWaitingInput(waiting.enterpriseId(), waiting.inputMessageId(), now);
        } else {
            events.append(waiting, "message.created",
                messages.find(waiting.enterpriseId(), waiting.conversationId(), waiting.inputMessageId()).orElseThrow(), now);
        }
    }

    public void change(AuthContext actor, String conversationId, String runId, boolean prioritize, Instant now) {
        var conversation = conversations.find(actor.enterpriseId(), actor.userId(), conversationId, true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var run = runs.find(actor.enterpriseId(), runId, true)
            .filter(row -> row.userId().equals(actor.userId()) && row.conversationId().equals(conversationId))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (!run.status().equals("waiting_turn")) {
            throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_NO_LONGER_QUEUED", "这条消息已离开队列。");
        }
        if (!prioritize) {
            cancel(run, now);
        } else if (!runs.waitingIds(actor.enterpriseId(), conversationId).getFirst().equals(runId)) {
            long position = -(conversation.lastSequence() + 1);
            runs.moveWaiting(run, position);
            var message = messages.find(run.enterpriseId(), conversationId, run.inputMessageId()).orElseThrow();
            events.append(run, "queue.updated", QueuedMessageView.from(runId, position, message), now);
        }
    }

    /** 交接已经提交，旧的执行只能释放本机资源。 */
    public static final class Continued extends RuntimeException {
        public Continued() {
            super("当前操作已完成，智能体将继续处理补充消息。", null, false, false);
        }
    }
}
