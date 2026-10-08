package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper.Scope;
import com.stonewu.agenteam.mapper.skill.SkillPromptMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.ToolConfirmation;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.model.workflow.entity.WorkflowNodeResult;
import com.stonewu.agenteam.service.agent.AgentExecutionAdapter;
import com.stonewu.agenteam.service.agent.AgentExecutionFactory;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 节点仍调用现有 AgentScope 对话适配器；技能、资料工具及研究子任务使用相同路径。
 */
@Component
public class WorkflowAgentNodeExecutor {
    private final AgentExecutionFactory factory;
    private final AgentExecutionAdapter adapter;
    private final RunApprovalService approvals;
    private final SkillPromptMapper skills;
    private final WorkflowValues values;
    private final ObjectMapper json;

    public WorkflowAgentNodeExecutor(AgentExecutionFactory factory, AgentExecutionAdapter adapter,
                                     RunApprovalService approvals,
                                     SkillPromptMapper skills, WorkflowValues values, ObjectMapper json) {
        this.factory = factory;
        this.adapter = adapter;
        this.approvals = approvals;
        this.skills = skills;
        this.values = values;
        this.json = json;
    }

    public Mono<WorkflowNodeResult> execute(WorkflowRunContext context, Identity identity, Node node, JsonNode input,
                                            Duration timeout) {
        String session = WorkflowRunContext.session(identity.id(), node.id());
        return Mono.using(() -> create(context, identity, node, input, timeout, session),
            task -> task.completion().then(Mono.fromCallable(() -> {
                if (!task.externalCalls().isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NESTING_UNSUPPORTED",
                        "工作流节点不能再启动其他工作流。");
                }
                if (!task.pendingApprovals().isEmpty()) {
                    return new WorkflowNodeResult(null, null,
                        List.of(new ToolConfirmation(session, task.pendingApprovals())), null);
                }
                if (task.result() == null || task.result().getTextContent().isBlank()) {
                    throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_EMPTY_RESULT",
                        "当前节点没有返回可用的结果。");
                }
                var result = json.createObjectNode().put("text", task.result().getTextContent());
                try {
                    result.set("result", values.read(task.result().getTextContent()));
                } catch (IllegalStateException plainText) {
                    result.put("result", task.result().getTextContent());
                }
                var citations = result.putArray("citations");
                var files = result.putArray("attachments");
                context.frames().update(mapper -> {
                    String group = mapper.presentation().id("workflow-node:" + identity.id() + ":" + node.id());
                    for (var block : mapper.presentation().blocks().values()) {
                        if (group.equals(block.parentBlockId())) {
                            if (block.citation() != null) {
                                citations.add(json.valueToTree(block.citation()));
                            }
                            if (block.file() != null) {
                                files.add(json.valueToTree(block.file()));
                            }
                        }
                    }
                    return List.of();
                });
                return WorkflowNodeResult.completed(result, "default");
            })), task -> {
                try {
                    task.close();
                } finally {
                    context.release(session);
                }
            });
    }

    private AgentExecutionAdapter.Task create(WorkflowRunContext context, Identity identity, Node node, JsonNode input,
                                              Duration timeout, String session) {
        context.requireActive();
        var dependency = WorkflowRunDefinitions.dependency(context.run(), "agent",
            node.config().path("agentVersionId").asText());
        var config = dependency.path("config");
        if (!config.path("workflowVersionIds").isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NESTING_UNSUPPORTED",
                "工作流节点不能再启动其他工作流。");
        }
        String content = values.write(input);
        if (node.type().equals("skill")) {
            content = skills.append(content,
                List.of(WorkflowRunDefinitions.dependency(context.run(), "skill",
                    node.config().path("skillVersionId").asText())));
        }
        boolean restored = context.states().get(context.run().userId(), session, "agent_state", AgentState.class)
            .isPresent();
        var initial = UserMessage.builder().id(session + ":input").textContent(content).build();
        var messages = approvals.input(context.run(), context.states(), session, initial, restored);
        var execution = factory.createScoped(context.run(), context.lease(), config,
            dependency.path("resourceId").asText(), dependency.path("name").asText(), session, context.states(),
            restored, timeout, ignored -> {
            });
        try {
            execution.guard().researchSlots(context.researchSlots());
            execution.guard()
                .boundary((runtime, tools, cost, phase) -> context.boundary(session, runtime, tools, cost, phase));
            context.frames().update(mapper -> {
                String parent = mapper.presentation().id("workflow-node:" + identity.id() + ":" + node.id());
                mapper.scope(new Scope(session, parent, parent, dependency.path("name").asText()));
                return List.of();
            });
            var task = adapter.createScoped(context.run(), context.lease(), execution, context.frames(), messages);
            context.cancellation(session, task::cancel);
            return task;
        } catch (RuntimeException failure) {
            execution.close();
            throw failure;
        }
    }
}
