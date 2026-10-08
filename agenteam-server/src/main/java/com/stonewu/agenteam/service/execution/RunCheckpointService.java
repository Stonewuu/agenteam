package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.RunActivityMapper;
import com.stonewu.agenteam.mapper.execution.RunCheckpointMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.agent.ExecutionPaths;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.state.AgentState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件内容确认后再进入短事务，检查点与消息一样拒绝旧租约。
 */
@Service
public class RunCheckpointService {
    public record Prepared(String stateKey, JsonNode state) {
    }

    private final RunCheckpointMapper checkpoints;
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final RunLifecycleService lifecycle;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final PayloadEncryption encryption;
    private final RunActivityMapper activity;
    private final ExecutionPaths paths;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RunCheckpointService(RunCheckpointMapper checkpoints, ConversationMapper conversations, RunMapper runs,
                                RunLifecycleService lifecycle,
                                ResourceJson json, ObjectMapper mapper, PayloadEncryption encryption,
                                ExecutionPaths paths, Clock clock, PlatformTransactionManager transactions,
                                RunActivityMapper activity) {
        this.checkpoints = checkpoints;
        this.conversations = conversations;
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.json = json;
        this.mapper = mapper;
        this.encryption = encryption;
        this.paths = paths;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactions);
        this.activity = activity;
    }

    public Prepared prepare(RunRecord run, EncryptedAgentStateStore states, AgentState live) {
        return prepareLiveStates(run, states, live == null ? List.of() : List.of(live));
    }

    public Prepared prepareLiveStates(RunRecord run, EncryptedAgentStateStore states, List<AgentState> active) {
        String id = UUID.randomUUID().toString();
        String key = paths.checkpointKey(run, id);
        var snapshot = new EncryptedAgentStateStore(paths.checkpoint(run, id), binding(run, run.leaseVersion(), key),
            encryption, mapper);
        states.copyTo(snapshot);
        for (var live : active) {
            if (!run.userId().equals(live.getUserId()) || live.getSessionId() == null || live.getSessionId()
                .isBlank()) {
                throw unavailable();
            }
            snapshot.save(run.userId(), live.getSessionId(), "agent_state", live);
        }
        var files = snapshot.manifest();
        if (files.isEmpty() || !snapshot.exists(run.userId(), run.conversationId())) {
            throw unavailable();
        }
        return new Prepared(key,
            json.tree(Map.of("version", 1, "files", files, "configurationHash", json.hash(run.executionConfig()))));
    }

    public void capture(RunRecord run, JobLease lease, EncryptedAgentStateStore states) {
        var prepared = prepare(run, states, null);
        transactions.executeWithoutResult(transaction -> save(lease, prepared));
    }

    public void captureLive(RunRecord run, JobLease lease, EncryptedAgentStateStore states, AgentState live) {
        if (live == null) {
            throw unavailable();
        }
        var prepared = prepare(run, states, live);
        transactions.executeWithoutResult(transaction -> {
            save(lease, prepared);
            activity.betweenSteps(run);
        });
    }

    public void save(JobLease lease, Prepared prepared) {
        var found = runs.find(lease.enterpriseId(), lease.runId(), false).orElseThrow();
        conversations.find(lease.enterpriseId(), lease.userId(), found.conversationId(), true).orElseThrow();
        var current = runs.find(lease.enterpriseId(), lease.runId(), true).orElseThrow();
        lifecycle.requireLease(current, lease);
        if (!json.hash(current.executionConfig()).equals(prepared.state().path("configurationHash").asText())) {
            throw unavailable();
        }
        checkpoints.save(lease, prepared.stateKey(), prepared.state(), clock.instant());
    }

    public boolean restore(RunRecord run, EncryptedAgentStateStore target) {
        if (checkpoints.find(run.enterpriseId(), run.id()).isEmpty()) {
            return false;
        }
        load(run).copyTo(target);
        return true;
    }

    public EncryptedAgentStateStore load(RunRecord run) {
        try {
            var checkpoint = checkpoints.find(run.enterpriseId(), run.id())
                .orElseThrow(RunCheckpointService::unavailable);
            if (checkpoint.leaseVersion() > run.leaseVersion() || !json.hash(run.executionConfig())
                .equals(checkpoint.state().path("configurationHash").asText())
                || checkpoint.state().path("version").asInt() != 1 || !checkpoint.state().path("files").isObject()) {
                throw unavailable();
            }
            var states = new EncryptedAgentStateStore(
                paths.checkpointPath(run, checkpoint.leaseVersion(), checkpoint.stateKey()),
                binding(run, checkpoint.leaseVersion(), checkpoint.stateKey()), encryption, mapper);
            Map<String, String> files = new HashMap<>();
            checkpoint.state().path("files").fields()
                .forEachRemaining(entry -> files.put(entry.getKey(), entry.getValue().asText()));
            if (files.isEmpty() || !states.matches(files)) {
                throw unavailable();
            }
            return states;
        } catch (RuntimeException invalid) {
            throw unavailable(invalid);
        }
    }

    private String binding(RunRecord run, long lease, String key) {
        return run.enterpriseId() + ":" + run.userId() + ":" + run.id() + ":" + lease + ":" + key;
    }

    private static ApiException unavailable() {
        return unavailable(null);
    }

    private static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.CONFLICT, "EXECUTION_STATE_UNAVAILABLE",
            "此对话的执行状态暂时无法读取，已保存的消息仍可查看，请联系维护者。", cause);
    }
}
