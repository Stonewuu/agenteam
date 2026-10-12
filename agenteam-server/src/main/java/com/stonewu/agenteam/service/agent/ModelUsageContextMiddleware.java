package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.function.Function;

/**
 * 用显式框架上下文确定当前会话，再随 Reactor 订阅传递不可变的计量归属。
 */
public final class ModelUsageContextMiddleware implements MiddlewareBase {
    private final ModelCallContext owner;

    public ModelUsageContextMiddleware(ModelCallContext owner) {
        this.owner = owner;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 2;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> next.apply(input))
            .contextWrite(values -> values.put(ModelCallContext.class, scope(context)));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                       Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> next.apply(input))
            .contextWrite(values -> values.put(ModelCallContext.class, scope(context)));
    }

    private ModelCallContext scope(RuntimeContext context) {
        if (!Objects.equals(owner.userId(), context.getUserId()) || context.getSessionId() == null) {
            throw new IllegalStateException("模型调用的用户或会话与执行归属不一致。");
        }
        return owner.forSession(context.getSessionId());
    }
}
