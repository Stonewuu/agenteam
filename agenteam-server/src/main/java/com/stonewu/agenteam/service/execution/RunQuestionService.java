package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.request.QuestionAnswerRequest;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 用户回答固定到待处理的问题，重复回答不会再次派发执行。 */
@Service
public class RunQuestionService {
    private final RunQuestionMapper questions;
    private final ToolCallMapper calls;
    private final RunMapper runs;
    private final EnterpriseMapper enterprises;
    private final RunLifecycleService lifecycle;
    private final ExecutionAccessService access;
    private final ConversationQueryService conversations;
    private final ToolCallTransactions transactions;
    private final RunQuestionContent content;
    private final QuestionConversationWriter writer;
    private final RunInteractionContinuation continuation;
    private final ObjectProvider<RunQuestionChannel> channels;
    private final Clock clock;

    public RunQuestionService(RunQuestionMapper questions, ToolCallMapper calls, RunMapper runs, EnterpriseMapper enterprises,
            RunLifecycleService lifecycle, ExecutionAccessService access, ConversationQueryService conversations,
            ToolCallTransactions transactions, RunQuestionContent content, QuestionConversationWriter writer,
            RunInteractionContinuation continuation, ObjectProvider<RunQuestionChannel> channels, Clock clock) {
        this.questions = questions;
        this.calls = calls;
        this.runs = runs;
        this.enterprises = enterprises;
        this.lifecycle = lifecycle;
        this.access = access;
        this.conversations = conversations;
        this.transactions = transactions;
        this.content = content;
        this.writer = writer;
        this.continuation = continuation;
        this.channels = channels;
        this.clock = clock;
    }

    public void park(RunRecord run, ToolCallRecord call) {
        var existing = questions.forTool(run.enterpriseId(), call.id());
        if (existing.isPresent()) {
            if (!"pending".equals(existing.get().getStatus())) {
                throw unavailable();
            }
            writer.save(run, existing.get());
            return;
        }
        var row = new RunQuestionRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(run.enterpriseId());
        row.setRunId(run.id());
        row.setToolCallId(call.id());
        row.setSessionId(call.frameworkSessionId());
        row.setUserId(run.userId());
        row.setRequestHash(call.requestHash());
        row.setStatus("pending");
        row.setRevision(1L);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        row.setExpiresAt(clock.instant().plusSeconds(86400));
        row.setQuestionEncrypted(content.encrypt(row, false, content.question(transactions.arguments(call))));
        channels.orderedStream().forEach(channel -> channel.initialize(run, row));
        if (questions.insert(row) != 1) {
            throw new IllegalStateException(Messages.text("error.execution.runQuestionService.park.1"));
        }
        calls.waitingInput(call, clock.instant());
        writer.save(run, row);
        var view = content.view(row);
        channels.orderedStream().forEach(channel -> channel.created(run, row, view));
    }

    public RunQuestionRow readable(AuthContext actor, String id) {
        var question = questions.find(actor.enterpriseId(), id).filter(value -> actor.userId().equals(value.getUserId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var run = runs.find(actor.enterpriseId(), question.getRunId(), false)
            .filter(value -> actor.userId().equals(value.userId())).orElseThrow(ResourceAuthorizationService::unavailable);
        conversations.readable(actor, run.conversationId());
        return question;
    }

    public List<RunQuestionView> list(AuthContext actor, String runId) {
        var run = runs.find(actor.enterpriseId(), runId, false).filter(value -> actor.userId().equals(value.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        conversations.readable(actor, run.conversationId());
        return questions.forRun(actor.enterpriseId(), runId).stream().map(content::view).toList();
    }

    public void authorizeAnswer(AuthContext actor, String id) {
        var question = readable(actor, id);
        if ("pending".equals(question.getStatus())) {
            access.actor(runs.find(actor.enterpriseId(), question.getRunId(), false).orElseThrow());
        }
    }

    @Transactional
    public RunQuestionView answer(AuthContext actor, String id, long revision, QuestionAnswerRequest input) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var initial = readable(actor, id);
        var run = lifecycle.locked(actor.enterpriseId(), initial.getRunId());
        var question = questions.find(actor.enterpriseId(), id).orElseThrow(ResourceAuthorizationService::unavailable);
        JsonNode answer = content.answer(question, input);
        if ("answered".equals(question.getStatus())) {
            if (content.read(question, true).path("answer").asText().equals(answer.path("answer").asText())) {
                return content.view(question);
            }
            throw new ApiException(HttpStatus.CONFLICT, "QUESTION_ALREADY_ANSWERED", Messages.text("error.execution.runQuestionService.answer.1"));
        }
        pending(run, question);
        if (question.getRevision() != revision) {
            throw ApiException.versionConflict(question.getRevision());
        }
        access.actor(run);
        var call = calls.find(actor.enterpriseId(), question.getToolCallId(), true).orElseThrow();
        if (!question.getRequestHash().equals(call.requestHash())) {
            throw unavailable();
        }
        String encrypted = content.encrypt(question, true, answer);
        if (questions.update(new LambdaUpdateWrapper<RunQuestionRow>().eq(RunQuestionRow::getEnterpriseId, actor.enterpriseId())
            .eq(RunQuestionRow::getId, id).eq(RunQuestionRow::getRevision, revision).eq(RunQuestionRow::getStatus, "pending")
            .set(RunQuestionRow::getStatus, "answered").set(RunQuestionRow::getAnswerEncrypted, encrypted)
            .set(RunQuestionRow::getAnsweredAt, clock.instant()).set(RunQuestionRow::getUpdatedAt, clock.instant())
            .setIncrBy(RunQuestionRow::getRevision, 1)) != 1) {
            throw unavailable();
        }
        transactions.answerQuestion(run, call, answer);
        var saved = questions.find(actor.enterpriseId(), id).orElseThrow();
        writer.save(run, saved);
        var view = content.view(saved);
        continuation.resumeWhenReady(run);
        var continued = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
        channels.orderedStream().forEach(channel -> channel.answered(continued, saved, view));
        return view;
    }

    @Transactional
    public void selectFreeText(AuthContext actor, String id) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var question = readable(actor, id);
        var run = lifecycle.locked(actor.enterpriseId(), question.getRunId());
        pending(run, question);
        access.actor(run);
        if (questions.update(new LambdaUpdateWrapper<RunQuestionRow>().eq(RunQuestionRow::getEnterpriseId, actor.enterpriseId())
            .eq(RunQuestionRow::getId, id).eq(RunQuestionRow::getStatus, "pending")
            .set(RunQuestionRow::getFreeTextSelectedAt, clock.instant())) != 1) {
            throw unavailable();
        }
    }

    public boolean answered(ToolCallRecord call) {
        return questions.forTool(call.enterpriseId(), call.id()).filter(row -> "answered".equals(row.getStatus())
            && call.requestHash().equals(row.getRequestHash())).isPresent();
    }

    private void pending(RunRecord run, RunQuestionRow question) {
        if (!"pending".equals(question.getStatus()) || !question.getExpiresAt().isAfter(clock.instant())
                || !Set.of("waiting_input", "waiting_approval").contains(run.status()) || run.cancelRequestedAt() != null) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "QUESTION_UNAVAILABLE", Messages.text("error.execution.runQuestionService.unavailable.1"));
    }
}
