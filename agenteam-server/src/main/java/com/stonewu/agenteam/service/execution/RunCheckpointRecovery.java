package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.RunActivityMapper;
import com.stonewu.agenteam.mapper.execution.RunCheckpointMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.AgentHistoryContext;
import com.stonewu.agenteam.service.agent.AgentSubagentHistory;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.agent.ExecutionPaths;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.state.AgentState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 读取经过校验的执行状态，用于恢复任务并判断能否从已完成的工具之后继续。 */
@Service
public final class RunCheckpointRecovery {
    private record ModelRetryState(RunRecord run, EncryptedAgentStateStore states, AgentState agentState) {
    }

    private final RunCheckpointMapper checkpoints;
    private final RunMapper runs;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final PayloadEncryption encryption;
    private final RunActivityMapper activity;
    private final ExecutionPaths paths;
    private final ToolCallMapper toolCalls;

    public RunCheckpointRecovery(RunCheckpointMapper checkpoints, RunMapper runs, ResourceJson json, ObjectMapper mapper,
                                 PayloadEncryption encryption, RunActivityMapper activity, ExecutionPaths paths, ToolCallMapper toolCalls) {
        this.checkpoints = checkpoints;
        this.runs = runs;
        this.json = json;
        this.mapper = mapper;
        this.encryption = encryption;
        this.activity = activity;
        this.paths = paths;
        this.toolCalls = toolCalls;
    }

    public boolean restore(RunRecord run, EncryptedAgentStateStore target) {
        if (checkpoints.find(run.enterpriseId(), run.id()).isEmpty()) {
            if (run.currentAttemptNo() > 1 && ConversationRunQueue.supported(run)) {
                throw unavailable();
            }
            return false;
        }
        load(run).copyTo(target);
        return true;
    }

    public boolean canRetry(RunRecord run) {
        if (!(run.status().equals("failed") || run.status().equals("cancelled")) || "TOOL_RESULT_UNKNOWN".equals(run.errorCode())) {
            return false;
        }
        // 后面已有其他执行时，不能用旧任务的上下文覆盖新对话。
        if (runs.latestTerminal(run.enterpriseId(), run.conversationId()).filter(run.id()::equals).isEmpty()) {
            return false;
        }
        if (!runs.retriedRuns(run.enterpriseId(), run.userId(), run.conversationId(), List.of(run.id())).isEmpty()) {
            return false;
        }
        if (!run.executionConfig().hasNonNull("retryOfRunId") && !toolCalls.hasSubmittedWrite(run)) {
            return true;
        }
        try {
            retryToolHistory(run);
            return true;
        } catch (ApiException failure) {
            if ("EXECUTION_STATE_UNAVAILABLE".equals(failure.code())) {
                return false;
            }
            throw failure;
        }
    }

    private Optional<ModelRetryState> modelRetryState(RunRecord previous) {
        if (!"model".equals(activity.get(previous).phase()) || checkpoints.find(previous.enterpriseId(), previous.id()).isEmpty()) {
            return Optional.empty();
        }
        var states = load(previous);
        return states.get(previous.userId(), previous.conversationId(), "agent_state", AgentState.class)
            .filter(value -> AgentHistoryContext.endsWithToolResultsForInput(value.getContext(), previous.inputMessageId()))
            .map(value -> new ModelRetryState(previous, states, value));
    }

    private Optional<ModelRetryState> retryToolHistory(RunRecord original) {
        var visited = new HashSet<String>();
        var candidate = original;
        while (visited.add(candidate.id())) {
            var state = modelRetryState(candidate);
            if (state.isPresent()) {
                return state;
            }
            if (toolCalls.hasSubmittedWrite(candidate) || "TOOL_RESULT_UNKNOWN".equals(candidate.errorCode())) {
                throw unavailable();
            }
            String parent = candidate.executionConfig().path("retryOfRunId").asText(null);
            if (parent == null) {
                return Optional.empty();
            }
            // 重试在开始前又被取消时，沿同一条用户消息找回上次已完成的工具结果。
            candidate = runs.find(original.enterpriseId(), parent, false)
                .filter(value -> value.userId().equals(original.userId()) && value.conversationId().equals(original.conversationId()))
                .orElseThrow(RunCheckpointRecovery::unavailable);
            if (!candidate.inputMessageId().equals(original.inputMessageId())) {
                return Optional.empty();
            }
        }
        throw unavailable();
    }

    /** 在原问题的下一次模型调用前继续，只复用已完成的工具结果。 */
    public boolean restoreModelRetry(RunRecord run, EncryptedAgentStateStore target) {
        String previousId = run.executionConfig().path("retryOfRunId").asText(null);
        if (previousId == null) {
            return false;
        }
        var previous = runs.find(run.enterpriseId(), previousId, false)
            .filter(value -> value.userId().equals(run.userId()) && value.conversationId().equals(run.conversationId()))
            .orElseThrow(RunCheckpointRecovery::unavailable);
        if (!previous.inputMessageId().equals(run.inputMessageId())) {
            return false;
        }
        var state = retryToolHistory(previous);
        if (state.isEmpty()) {
            return false;
        }
        var saved = state.get();
        var toolContext = AgentSubagentHistory.restore(run, saved.run(), saved.states(), saved.agentState(), target);
        target.save(run.userId(), run.conversationId(), "agent_state", AgentState.builder()
            .userId(run.userId()).sessionId(run.conversationId()).context(List.copyOf(saved.agentState().getContext()))
            .toolContext(toolContext).build());
        return true;
    }

    public EncryptedAgentStateStore load(RunRecord run) {
        try {
            var checkpoint = checkpoints.find(run.enterpriseId(), run.id())
                .orElseThrow(RunCheckpointRecovery::unavailable);
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

    static String binding(RunRecord run, long lease, String key) {
        return run.enterpriseId() + ":" + run.userId() + ":" + run.id() + ":" + lease + ":" + key;
    }

    static ApiException unavailable() {
        return unavailable(null);
    }

    private static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.CONFLICT, "EXECUTION_STATE_UNAVAILABLE",
            Messages.text("error.execution.runCheckpointService.unavailable.1"), cause);
    }
}
