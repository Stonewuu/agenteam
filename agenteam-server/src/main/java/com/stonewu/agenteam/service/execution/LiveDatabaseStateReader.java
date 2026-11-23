package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunJobMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 缓存初始化及恢复使用独立的短读取事务，不复用调用者已经提交的事务。
 */
@Service
public class LiveDatabaseStateReader {
    private final ConversationMapper conversations;
    private final ExecutionMessageMapper messages;
    private final RunMapper runs;
    private final RunJobMapper jobs;
    private final Clock clock;

    public LiveDatabaseStateReader(ConversationMapper conversations, ExecutionMessageMapper messages,
                                   RunMapper runs, RunJobMapper jobs, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.jobs = jobs;
        this.clock = clock;
    }

    public record Baseline(ConversationRecord conversation, List<ContentBlock> blocks) {
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ConversationRecord conversation(String enterprise, String user, String id) {
        return conversations.find(enterprise, user, id, false).orElseThrow();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Baseline read(RunRecord run) {
        var conversation = conversations.find(run.enterpriseId(), run.userId(), run.conversationId(), false)
            .orElseThrow();
        var output = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId());
        return new Baseline(conversation, output.map(value -> value.blocks()).orElse(List.of()));
    }

    public record Recovery(ConversationRecord conversation, String outputMessage, Map<String, String> revisions) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Recovery recovery(String enterprise, String user, String id) {
        var conversation = conversations.find(enterprise, user, id, false).orElseThrow();
        if (conversation.activeRunId() == null) {
            return new Recovery(conversation, null, Map.of());
        }
        var active = runs.find(enterprise, conversation.activeRunId(), false).orElseThrow();
        var output = messages.find(enterprise, id, active.outputMessageId()).orElseThrow();
        var revisions = output.blocks().stream().collect(Collectors.toMap(
            block -> "block:" + output.id() + ":" + block.id(), ContentBlock::revision));
        return new Recovery(conversation, output.id(), revisions);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireLease(RunRecord run, JobLease lease) {
        var current = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
        if (current.terminal() || current.leaseVersion() != lease.version() || !jobs.valid(lease, clock.instant(),
            false)) {
            throw new ExecutionStoppedException();
        }
    }
}
