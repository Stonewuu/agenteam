package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 父子任务共享执行次数与步骤预算，每次外部调用前检查领取资格和当前授权。
 */
public final class ExecutionGuardMiddleware implements MiddlewareBase {
    @FunctionalInterface
    public interface StepBoundary {
        void before(RuntimeContext context, List<String> tools, int modelCost, String phase);
    }

    public static final String SESSION_METADATA = "agenteamSessionId";
    public static final String OWNER_SESSION_METADATA = "agenteamOwnerSessionId";

    private record Invocation(Agent agent, RuntimeContext context) {
    }

    private final RunLifecycleService lifecycle;
    private final JobLease lease;
    private final String rootSession;
    private final int maxSteps;
    private final AtomicInteger steps = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private Semaphore childSlots = new Semaphore(2);
    private Set<String> subagentIds = Set.of();
    private final Set<Invocation> active = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> childOutcomes = new ConcurrentHashMap<>();
    private volatile Runnable beforeStep = () -> {
    };
    private ExecutionToolRuntime tools;
    private Consumer<RuntimeContext> checkpoint = context -> {
    };
    private StepBoundary boundary;

    public ExecutionGuardMiddleware(RunLifecycleService lifecycle, JobLease lease, String rootSession, int maxSteps) {
        this.lifecycle = lifecycle;
        this.lease = lease;
        this.rootSession = rootSession;
        this.maxSteps = maxSteps;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(subscriber -> {
            String call = subscriber.getOrDefault(SubagentToolEvents.PARENT_CALL, null);
            boolean child = !rootSession.equals(context.getSessionId());
            Flux<AgentEvent> execution = Flux.defer(() -> {
                if (stopped.get()) {
                    return Flux.error(new ExecutionStoppedException());
                }
                if (child && !childSlots.tryAcquire()) {
                    return Flux.error(
                        new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_LIMIT", "同一任务最多同时执行两个子任务。"));
                }
                var invocation = new Invocation(agent, context);
                active.add(invocation);
                return Flux.defer(() -> next.apply(input)).map(event -> stamp(event, context)).doFinally(signal -> {
                    active.remove(invocation);
                    if (child) {
                        childSlots.release();
                    }
                });
            });
            if (!child || call == null) {
                return execution;
            }
            return execution.doOnComplete(
                    () -> childOutcomes.putIfAbsent(call, stopped.get() ? "cancelled" : "completed"))
                .doOnError(failed -> childOutcomes.put(call,
                    failed instanceof ExecutionStoppedException ? "cancelled" : "failed"))
                .doOnCancel(() -> childOutcomes.putIfAbsent(call, "cancelled"));
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (tools != null) {
            tools.modelContext(context.getSessionId(), input.model().getContextWindowSize());
        }
        return Mono.fromCallable(() -> {
            if (stopped.get()) {
                throw new ExecutionStoppedException();
            }
            if (tools != null) {
                tools.requireActive();
            }
            var compact = tools == null ? input : tools.shortenHistory(agent, context, input);
            AgentContextBudget.requireFits(compact);
            return compact;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(compact -> check(context, List.of(), 1, "model")
            .thenMany(Flux.defer(() -> next.apply(compact)))).map(event -> stamp(event, context));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        boolean child = !rootSession.equals(context.getSessionId());
        for (var call : input.toolCalls()) {
            if (tools != null && tools.allowed(agent, call.getName(), child)) {
                continue;
            }
            if (child || subagentIds.isEmpty() || !Set.of("agent_spawn", "agent_send").contains(call.getName())
                || (call.getName().equals("agent_spawn") && !subagentIds.contains(call.getInput().get("agent_id")))) {
                return Flux.error(new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                    "当前任务不能使用所请求的工具。"));
            }
        }
        var keys = input.toolCalls().stream().map(call -> context.getSessionId() + ":" + call.getId()).toList();
        return check(context, keys, 0, "tools").thenMany(Flux.deferContextual(subscriber -> {
            if (tools != null) {
                tools.prepare(agent, context, input, subscriber.getOrDefault(SubagentToolEvents.PARENT_CALL, null));
            }
            return next.apply(input);
        })).map(event -> stamp(event, context));
    }

    public void cancel() {
        stopped.set(true);
        if (tools != null) {
            tools.close();
        }
        for (var invocation : active) {
            if (invocation.agent() instanceof HarnessAgent harness) {
                harness.getDelegate().interrupt(invocation.context());
            } else if (invocation.agent() instanceof ReActAgent react) {
                react.interrupt(invocation.context());
            }
        }
    }

    public void beforeStep(Runnable action) {
        beforeStep = action;
    }

    public Mono<Void> beforeCompaction(RuntimeContext context) {
        return check(context, List.of(), 1, "model");
    }

    public void tools(ExecutionToolRuntime runtime) {
        tools = runtime;
    }

    public void checkpoint(Consumer<RuntimeContext> action) {
        checkpoint = action;
    }

    public void boundary(StepBoundary action) {
        boundary = action;
    }

    public void subagents(Set<String> ids) {
        subagentIds = Set.copyOf(ids);
    }

    public void researchSlots(Semaphore slots) {
        if (!active.isEmpty()) {
            throw new IllegalStateException("开始执行后不能更换子任务的并发限制");
        }
        childSlots = slots;
    }

    public String childOutcome(String call) {
        return childOutcomes.get(call);
    }

    private Mono<Void> check(RuntimeContext context, List<String> toolKeys, int modelCost, String phase) {
        return Mono.<Void>fromRunnable(() -> {
            if (stopped.get()) {
                throw new ExecutionStoppedException();
            }
            if (tools != null) {
                tools.requireActive();
            }
            if (maxSteps > 0 && steps.addAndGet(modelCost + toolKeys.size()) > maxSteps) {
                throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_STEP_LIMIT",
                    "本次任务已达到允许的步骤上限，请缩小任务范围后重试。");
            }
            beforeStep.run();
            if (boundary != null) {
                boundary.before(context, toolKeys, modelCost, phase);
                return;
            }
            lifecycle.beforeExternalStep(lease);
            boolean root = rootSession.equals(context.getSessionId());
            if (root) {
                checkpoint.accept(context);
            }
            lifecycle.reserveSteps(lease, toolKeys, modelCost, root ? phase : "subagent");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private AgentEvent stamp(AgentEvent event, RuntimeContext context) {
        if (event.getMetadata() == null || !event.getMetadata().containsKey(OWNER_SESSION_METADATA)) {
            event.withMetadataEntry(OWNER_SESSION_METADATA, rootSession);
        }
        if ((event.getMetadata() == null || !event.getMetadata().containsKey(SESSION_METADATA))
            && (event.getSource() == null || !rootSession.equals(context.getSessionId()))) {
            event.withMetadataEntry(SESSION_METADATA, context.getSessionId());
        }
        return event;
    }
}
