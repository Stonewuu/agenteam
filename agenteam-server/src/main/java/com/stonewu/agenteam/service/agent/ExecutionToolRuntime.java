package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.model.tool.entity.ToolInvocationIdentity;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.*;
import com.stonewu.agenteam.service.workspace.WorkspaceResultGrants;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 父子智能体各用自己的固定工具范围；子任务只注册已验证的只读工具。
 */
public final class ExecutionToolRuntime implements AutoCloseable {
    private final RunRecord run;
    private final JobLease lease;
    private final Map<String, ExecutionToolBinding> bindings;
    private final Map<String, ExecutionToolBinding> allBindings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ExecutionToolBinding>> childBindings = new ConcurrentHashMap<>();
    private final Map<Agent, Map<String, ExecutionToolBinding>> agentBindings = new ConcurrentHashMap<>();
    private final ToolCallTransactions transactions;
    private final ToolExecutionService execution;
    private final ToolCallMapper calls;
    private final ResourceJson json;
    private final Duration timeout;
    private final long startedAt = System.nanoTime();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<ApiException> failure = new AtomicReference<>();
    private final Set<AtomicReference<ToolCallControl>> active = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Set<String> externalNames = ConcurrentHashMap.newKeySet();
    private final Map<String, String> previousToolNames = new LinkedHashMap<>();
    private final Map<String, Integer> contextWindows = new ConcurrentHashMap<>();
    private final Map<String, Integer> resultLimits = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sharedResults = new ConcurrentHashMap<>();
    private final Set<String> invalidArguments = ConcurrentHashMap.newKeySet();

    public ExecutionToolRuntime(RunRecord run, JobLease lease, Map<String, ExecutionToolBinding> bindings,
                                ToolCallTransactions transactions,
                                ToolExecutionService execution, ToolCallMapper calls, ResourceJson json,
                                Duration timeout) {
        this.run = run;
        this.lease = lease;
        this.bindings = bindings;
        this.transactions = transactions;
        this.execution = execution;
        this.calls = calls;
        this.json = json;
        this.timeout = timeout;
        allBindings.putAll(bindings);
        String previous = run.executionConfig().path("previousRunId").asText(null);
        if (previous != null) {
            for (var call : calls.forRun(run.enterpriseId(), previous)) {
                String alias = ExecutionToolBinding.alias(call.toolName(), call.pluginToolId(),
                    call.resourceVersionId(), call.resourceId());
                previousToolNames.put(alias, call.toolName());
            }
        }
    }

    public Map<String, String> modelToolNames() {
        var result = new LinkedHashMap<>(previousToolNames);
        allBindings.forEach((alias, binding) -> result.put(alias, binding.definition().name()));
        externalNames.forEach(alias -> result.put(alias, "run_workflow"));
        return Map.copyOf(result);
    }

    public Map<String, ExecutionToolBinding> bindings() {
        return Map.copyOf(allBindings);
    }

    public void modelContext(String session, int contextLength) {
        contextWindows.put(session, contextLength);
    }

    public ModelCallInput shortenHistory(Agent agent, RuntimeContext context, ModelCallInput input) {
        var latest = ToolResultHistory.latestUses(input.messages());
        Map<String, ToolResultBlock> replacements = new LinkedHashMap<>();
        int limit = ToolResultBudget.perCall(execution.resultSettings(), input.model().getContextWindowSize(),
            Math.max(1, latest.size()));
        for (var result : ToolResultHistory.latestResults(input.messages())) {
            if (latest.containsKey(result.getId()) && result.getName() != null
                && (Set.of("agent_spawn", "agent_send").contains(result.getName()) || externalNames.contains(
                result.getName()))) {
                int size = Utf8Text.size(DelegatedToolResultService.text(result));
                if (result.getMetadata().get(DelegatedToolResultService.REFERENCE) instanceof Map<?, ?> reference) {
                    if (size > limit) {
                        String header = String.valueOf(
                            result.getMetadata().getOrDefault(DelegatedToolResultService.HEADER, ""));
                        String compact = DelegatedToolResultService.format((ObjectNode) json.tree(reference), header,
                            limit);
                        replacements.put(result.getId(), new ToolResultBlock(result.getId(), result.getName(),
                            List.of(TextBlock.builder().text(compact).build()), result.getMetadata(),
                            result.getState()));
                    }
                } else if (size > Math.min(limit, execution.resultSettings().previewBytes() * 2)) {
                    replacements.put(result.getId(),
                        execution.delegatedResult(run, lease, context.getSessionId(), latest.get(result.getId()),
                            result, limit));
                }
            }
        }
        var normalized = ToolResultHistory.replaceLatest(input.messages(), replacements);
        var keep = ToolResultHistory.latestCalls(input.messages());
        int maximum = execution.resultSettings().previewBytes();
        var state = RuntimeContext.resolveAgentState(context, agent);
        if (state != null) {
            var mutable = state.contextMutable();
            var compact = ToolResultHistory.shorten(ToolResultHistory.replaceLatest(mutable, replacements), keep, json,
                maximum);
            for (int i = 0; i < mutable.size(); i++) {
                mutable.set(i, compact.get(i));
            }
        }
        return new ModelCallInput(ToolResultHistory.shorten(normalized, keep, json, maximum), input.tools(),
            input.options(), input.model());
    }

    public boolean allowed(Agent agent, String name, boolean child) {
        return agentBindings.getOrDefault(agent, Map.of()).containsKey(name) || (!child && externalNames.contains(
            name));
    }

    public void childTools(String id, Map<String, ExecutionToolBinding> configured) {
        var reads = configured.entrySet().stream().filter(entry -> entry.getValue().readOnly())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        childBindings.put(id, reads);
        allBindings.putAll(reads);
    }

    public void externalTools(HarnessAgent agent, Map<String, ToolBase> tools) {
        for (var entry : tools.entrySet()) {
            if (bindings.containsKey(entry.getKey()) || !entry.getKey().equals(entry.getValue().getName())) {
                throw new IllegalStateException("外部工具名称与当前工具冲突");
            }
            externalNames.add(entry.getKey());
            agent.getToolkit().registerAgentTool(entry.getValue());
        }
    }

    public void attach(HarnessAgent agent, boolean child) {
        attach(agent, child ? bindings.entrySet().stream().filter(entry -> entry.getValue().readOnly())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)) : bindings);
    }

    public void attachChild(HarnessAgent agent, String id) {
        attach(agent, childBindings.getOrDefault(id, Map.of()));
    }

    private void attach(HarnessAgent agent, Map<String, ExecutionToolBinding> selected) {
        var exported = new LinkedHashMap<String, ExecutionToolBinding>();
        var identities = new HashSet<ToolInvocationIdentity>();
        for (var binding : selected.values().stream().sorted(
            Comparator.comparing((ExecutionToolBinding value) -> value.source() != null)
                .thenComparing(ExecutionToolBinding::alias)).toList()) {
            if (identities.add(binding.identity())) {
                exported.put(binding.alias(), binding);
            }
        }
        agentBindings.put(agent, exported);
        agentBindings.put(agent.getDelegate(), exported);
        for (var binding : exported.values()) {
            agent.getToolkit().registerAgentTool(new PlatformTool(binding));
        }
    }

    public void prepare(Agent agent, RuntimeContext context, ActingInput input, String parentCall) {
        requireActive();
        String session = context.getSessionId();
        sessions.put(parentCall == null ? session : session + ":" + parentCall, session);
        sharedResults.put(session, WorkspaceResultGrants.from(context));
        int maximum = ToolResultBudget.perCall(execution.resultSettings(), contextWindows.getOrDefault(session, 0),
            input.toolCalls().size());
        for (var use : input.toolCalls()) {
            var binding = agentBindings.getOrDefault(agent, Map.of()).get(use.getName());
            if (binding != null) {
                var call = transactions.prepare(lease, binding, session, parentCall, use);
                if (call.status().equals("failed") && "TOOL_ARGUMENTS_INVALID".equals(call.errorCode())) {
                    invalidArguments.add(binding.alias() + ":" + json.hash(json.tree(use.getInput())));
                }
                resultLimits.put(session + ":" + use.getId(), maximum);
            }
        }
    }

    public ToolCallRecord find(String source, String call) {
        var session = source.equals(run.conversationId()) ? run.conversationId() : sessions.get(source);
        return session == null ? null : calls.framework(run, session, call).orElse(null);
    }

    public void requireActive() {
        if (failure.get() != null) {
            throw failure.get();
        }
        if (stopped.get()) {
            throw new ExecutionStoppedException();
        }
    }

    public void requireSuccessful() {
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    @Override
    public void close() {
        stopped.set(true);
        active.forEach(value -> {
            var control = value.get();
            if (control != null) {
                control.close();
            }
        });
    }

    private Duration remaining() {
        if (timeout.isZero()) {
            return Duration.ZERO;
        }
        long left = timeout.toNanos() - (System.nanoTime() - startedAt);
        if (left <= 0) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "EXECUTION_TIMEOUT", "本次执行已超时。");
        }
        return Duration.ofNanos(left);
    }

    private final class PlatformTool extends ToolBase {
        private final ExecutionToolBinding binding;

        private PlatformTool(ExecutionToolBinding binding) {
            super(ToolBase.builder().name(binding.alias()).description(binding.resourceName()
                    + (binding.source() != null && !binding.source().name()
                    .equals(binding.resourceName()) ? " · " + binding.source().name() : "") + "：" + binding.definition()
                    .description())
                .inputSchema(json.object(binding.definition().inputSchema())).readOnly(binding.readOnly())
                .concurrencySafe(binding.readOnly()));
            this.binding = binding;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
            return Mono.fromCallable(() -> {
                requireActive();
                if (invalidArguments.contains(binding.alias() + ":" + json.hash(json.tree(input)))) {
                    // 调用已保存为参数错误，只读取该错误结果，不发送操作或申请写入确认。
                    return PermissionDecision.allow("本次调用只返回已经保存的参数校验错误。");
                }
                if (transactions.rejected(run, binding, json.tree(input))) {
                    return PermissionDecision.deny("用户已拒绝本次操作，不能对相同参数再次请求确认。");
                }
                return ToolApprovalPolicy.forRun(run).requiresApproval(binding.definition().operationClass())
                    ? PermissionDecision.ask("本次工具操作需要由原发起人确认。") : PermissionDecision.allow(
                    "当前会话策略允许执行此工具。");
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.defer(() -> {
                var cancellation = new AtomicReference<ToolCallControl>();
                var cancelled = new AtomicBoolean();
                active.add(cancellation);
                String key = param.getRuntimeContext().getSessionId() + ":" + param.getToolUseBlock().getId();
                int maximum = resultLimits.getOrDefault(key, execution.resultSettings()
                    .inlineBytes(contextWindows.getOrDefault(param.getRuntimeContext().getSessionId(), 0)));
                return Mono.fromCallable(() -> {
                        requireActive();
                        if (binding.definition().name().equals("view_image")) {
                            var output = execution.invokeImage(run, lease, binding,
                                param.getRuntimeContext().getSessionId(), param.getToolUseBlock(), remaining(),
                                cancellation,
                                () -> stopped.get() || cancelled.get(), maximum,
                                sharedResults.getOrDefault(param.getRuntimeContext().getSessionId(), Set.of()));
                            return ToolResultBlock.builder().id(param.getToolUseBlock().getId()).name(getName())
                                .output(output.content())
                                .state(output.result().path("isError")
                                    .asBoolean(false) ? ToolResultState.ERROR : ToolResultState.SUCCESS).build();
                        }
                        JsonNode result = execution.invoke(run, lease, binding, param.getRuntimeContext().getSessionId(),
                            param.getToolUseBlock(), remaining(), cancellation,
                            () -> stopped.get() || cancelled.get(), maximum,
                            sharedResults.getOrDefault(param.getRuntimeContext().getSessionId(), Set.of()));
                        return ToolResultBlock.builder().id(param.getToolUseBlock().getId()).name(getName())
                            .output(TextBlock.builder().text(result.toString()).build())
                            .state(
                                result.path("isError").asBoolean(false) ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                            .build();
                    }).subscribeOn(Schedulers.boundedElastic()).doOnError(error -> {
                        failure.compareAndSet(null, error instanceof ApiException known ? known :
                            new ApiException(HttpStatus.CONFLICT, "TOOL_EXECUTION_FAILED",
                                "本次工具调用无法继续，已保存结果仍可查看。"));
                    }).doOnCancel(() -> {
                        cancelled.set(true);
                        var control = cancellation.get();
                        if (control != null) {
                            control.close();
                        }
                    })
                    .doFinally(ignored -> {
                        active.remove(cancellation);
                        resultLimits.remove(key);
                    });
            });
        }
    }

}
