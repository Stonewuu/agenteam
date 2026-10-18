package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.model.execution.request.PreviewInput;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.model.workflow.request.WorkflowPreviewInput;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.project.ConversationProjectService;
import com.stonewu.agenteam.service.skill.SkillExecutionService;
import com.stonewu.agenteam.service.collaboration.CollaborationSubmissionRecords;
import com.stonewu.agenteam.service.collaboration.CollaborationSourceAccess;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkLease;
import com.stonewu.agenteam.service.usage.QuotaReservationService;
import com.stonewu.agenteam.service.workflow.WorkflowControlNodes;
import com.stonewu.agenteam.service.workflow.WorkflowGraphValidator;
import com.stonewu.agenteam.service.workflow.WorkflowValues;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次提交只保存一次后台执行；事务提交前没有模型订阅或外部调用。
 */
@Service
public class RunSubmissionService {
    @Value("${execution.events.live-enabled:true}")
    private boolean liveEvents;
    private final ConversationMapper conversations;
    private final ExecutionMessageMapper messages;
    private final RunMapper runs;
    private final RunJobMapper jobs;
    private final ExecutionEventMapper events;
    private final ExecutionConfigurationService configuration;
    private final QuotaReservationService quotas;
    private final EnterpriseAuthorizationService authorization;
    private final Clock clock;
    private final SkillExecutionService skills;
    private final ExecutionSourceService sources;
    private final EnterpriseMapper enterprises;
    private final WorkflowGraphValidator workflowGraphs;
    private final WorkflowControlNodes workflowInputs;
    private final WorkflowValues workflowValues;
    private final ConversationProjectService projects;
    private final CollaborationSubmissionRecords collaboration;
    private final CollaborationSourceAccess collaborationSources;
    private final ConversationTitleService titles;

    public RunSubmissionService(ConversationMapper conversations, ExecutionMessageMapper messages, RunMapper runs,
                                RunJobMapper jobs,
                                ExecutionEventMapper events, ExecutionConfigurationService configuration,
                                QuotaReservationService quotas, EnterpriseAuthorizationService authorization,
                                Clock clock, SkillExecutionService skills, ExecutionSourceService sources,
                                EnterpriseMapper enterprises, WorkflowGraphValidator workflowGraphs,
                                WorkflowControlNodes workflowInputs, WorkflowValues workflowValues,
                                ConversationProjectService projects, CollaborationSubmissionRecords collaboration, CollaborationSourceAccess collaborationSources,
                                ConversationTitleService titles) {
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.jobs = jobs;
        this.events = events;
        this.configuration = configuration;
        this.quotas = quotas;
        this.authorization = authorization;
        this.clock = clock;
        this.skills = skills;
        this.sources = sources;
        this.enterprises = enterprises;
        this.workflowGraphs = workflowGraphs;
        this.workflowInputs = workflowInputs;
        this.workflowValues = workflowValues;
        this.projects = projects;
        this.collaboration = collaboration;
        this.collaborationSources = collaborationSources;
        this.titles = titles;
    }

    @Transactional
    public RunAccepted create(AuthContext actor, NewConversationInput request) {
        authorization.lockAndRequire(actor, "agent.run");
        var input = configuration.input(request.input());
        var selected = configuration.normal(actor, request.agentId(), null, input.modelSelection());
        String conversation = UUID.randomUUID().toString();
        conversations.create(conversation, actor.enterpriseId(), actor.userId(), selected.agentId(),
            selected.versionId(), selected.hireId(),
            title(actor, input.text()), "normal", ToolApprovalPolicy.from(request.approvalPolicy()).value(),
            clock.instant());
        var accepted = submit(actor, projects.initial(actor.enterpriseId(), actor.userId(), conversation, request.projectId()),
            selected, input, null);
        titles.enqueue(actor, accepted, ModelSelection.fromConfig(selected.snapshot().path("config")));
        return accepted;
    }

    @Transactional
    public RunAccepted send(AuthContext actor, String id, MessageInput input) {
        return send(actor, id, input, null);
    }

    private RunAccepted send(AuthContext actor, String id, MessageInput input, String retryOf) {
        authorization.lockAndRequire(actor, "agent.run");
        collaboration.requireWebConversation(actor, id);
        input = configuration.input(input);
        var conversation = conversations.find(actor.enterpriseId(), actor.userId(), id, true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        active(conversation);
        if (!conversation.mode().equals("normal")) {
            throw ApiException.invalidField("conversationId", "预览对话不能继续发送，请重新发起预览。");
        }
        var modelSelection = input.modelSelection() == null ? conversation.modelSelection() : input.modelSelection();
        var selected = configuration.normal(actor, conversation.agentId(), conversation.agentVersionId(),
            modelSelection);
        return submit(actor, conversation, selected, input, retryOf);
    }

    @Transactional
    public RunAccepted preview(AuthContext actor, String agent, PreviewInput input) {
        authorization.lockAndRequire(actor, "agent.preview");
        var message = configuration.input(input.input());
        var selected = configuration.previewInput(actor, agent, input);
        String conversation = UUID.randomUUID().toString();
        conversations.create(conversation, actor.enterpriseId(), actor.userId(), agent, null, null,
            title(actor, message.text()), "preview", clock.instant());
        return submit(actor, conversations.find(actor.enterpriseId(), actor.userId(), conversation, true).orElseThrow(),
            selected, message, null);
    }

    @Transactional
    public RunAccepted workflowPreview(AuthContext actor, String workflow, WorkflowPreviewInput input) {
        authorization.lockAndRequire(actor, "workflow.preview");
        var selected = configuration.workflowPreview(actor, workflow, input.draft());
        workflowInputs.validateInput(workflowGraphs.compile(selected.snapshot().path("config")), input.input());
        String content = workflowValues.write(input.input());
        selected.snapshot().set("workflowInput", input.input().deepCopy());
        var message = new MessageInput(content, List.of(), List.of(), List.of(), List.of());
        String conversation = UUID.randomUUID().toString();
        conversations.createWorkflowPreview(conversation, actor.enterpriseId(), actor.userId(), workflow,
            title(actor, selected.name()), clock.instant());
        return submit(actor, conversations.find(actor.enterpriseId(), actor.userId(), conversation, true).orElseThrow(),
            selected, message, null);
    }

    @Transactional
    public RunAccepted retry(AuthContext actor, String runId) {
        authorization.lockAndRequire(actor, "agent.run");
        var original = runs.find(actor.enterpriseId(), runId, false)
            .filter(value -> value.userId().equals(actor.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (!(original.status().equals("failed") || original.status()
            .equals("cancelled")) || "TOOL_RESULT_UNKNOWN".equals(original.errorCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_RETRY_UNAVAILABLE",
                "此任务当前不能重试，请先核对执行结果。");
        }
        return send(actor, original.conversationId(), messages.input(actor.enterpriseId(), original.inputMessageId()),
            original.id());
    }

    private RunAccepted submit(AuthContext actor, ConversationRecord conversation,
                               ExecutionConfigurationService.Selection selected, MessageInput input, String retryOf) {
        active(conversation);
        if (conversation.activeRunId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                "当前对话还有未结束的任务，请等待完成或先停止。");
        }
        var prepared = prepare(actor, conversation.id(), selected, input, retryOf);
        quotas.reserve(actor, prepared.run());
        var modelSelection = ModelSelection.fromConfig(selected.snapshot().path("config"));
        if (modelSelection != null && !modelSelection.equals(conversation.modelSelection())) {
            conversations.selectModel(conversation, modelSelection, clock.instant());
        }
        return persist(actor, conversation, selected, input, prepared,
            conversation.mode().equals("preview") ? "preview" : "interactive", 1);
    }

    /**
     * 每次计划建立独立会话；次数不足时不产生会话、消息或后台任务。
     */
    @Transactional
    public Optional<RunAccepted> scheduled(AuthContext actor, ExecutionConfigurationService.Selection selected,
                                           String text,
                                           String scheduleId, String occurrenceId, boolean manual, int maxRetries) {
        authorization.lockAndRequire(actor, "agent.run");
        var input = configuration.input(new MessageInput(text, List.of(), List.of(), List.of(), List.of()));
        var prepared = prepare(actor, null, selected, input, null);
        prepared.fixed().put("scheduleId", scheduleId).put("scheduleOccurrenceId", occurrenceId);
        if (!quotas.tryReserve(actor, prepared.run())) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        conversations.create(id, actor.enterpriseId(), actor.userId(), selected.agentId(), selected.versionId(),
            selected.hireId(), title(actor, text), "normal", clock.instant());
        var conversation = conversations.find(actor.enterpriseId(), actor.userId(), id, true).orElseThrow();
        return Optional.of(
            persist(actor, conversation, selected, input, prepared, manual ? "manual_schedule" : "scheduled",
                maxRetries + 1));
    }

    /** 岗位执行复用普通执行、配额和后台工作，关联与 Run 在同一事务内提交。 */
    @Transactional
    public RunAccepted collaboration(AuthContext actor, CollaborationWorkLease lease, String contextId, int round) {
        authorization.lockAndRequire(actor, "agent.run");
        var replay = collaboration.replay(actor, lease, round);
        if (replay.isPresent()) {
            return replay.get();
        }
        var preparedRole = collaboration.prepare(actor, lease, contextId, round);
        var role = preparedRole.participant();
        var selected = configuration.normal(actor, role.getAgentId(), role.getAgentVersionId());
        boolean planningOnly = collaboration.planningOnly(preparedRole);
        selected.snapshot().put("sourceType", "collaboration");
        selected.snapshot().put("collaborationInputContext", preparedRole.text());
        selected.snapshot().putObject("collaboration").put("taskId", preparedRole.task().getId())
            .put("workItemId", preparedRole.work().getId()).put("participantId", role.getId()).put("contextId", contextId)
            .put("inputRevision", preparedRole.task().getInputRevision()).put("runRound", round).put("planningOnly", planningOnly);
        if (planningOnly) {
            ((ObjectNode) selected.snapshot().path("config")).putArray("workflowVersionIds");
        }
        String id = role.getConversationId();
        String approvalPolicy = collaboration.approvalPolicy(preparedRole);
        if (id == null) {
            id = UUID.randomUUID().toString();
            conversations.create(id, actor.enterpriseId(), actor.userId(), selected.agentId(), selected.versionId(), selected.hireId(),
                preparedRole.task().getShortCode() + " " + role.getRoleName(), "normal", approvalPolicy, clock.instant());
            projects.initial(actor.enterpriseId(), actor.userId(), id, role.getProjectId());
        }
        var conversation = conversations.find(actor.enterpriseId(), actor.userId(), id, true).orElseThrow(ResourceAuthorizationService::unavailable);
        if (!role.getProjectId().equals(conversation.projectId()) || !role.getAgentId().equals(conversation.agentId())
            || !role.getAgentVersionId().equals(conversation.agentVersionId()) || !conversation.approvalPolicy().equals(approvalPolicy)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_CONVERSATION_CHANGED", "岗位会话的项目、版本或执行策略已被修改。");
        }
        // 内容来自已经校验长度与摘要的服务端上下文，作为用户资料传入，不提升为系统指令。
        var attachments = collaborationSources.files(actor, selected.snapshot()).stream().map(FileRecord::id).toList();
        var input = new MessageInput(collaboration.displayText(preparedRole), attachments, List.of(), List.of(), List.of());
        var accepted = submit(actor, conversation, selected, input, null);
        collaboration.saved(preparedRole, lease, accepted);
        return accepted;
    }

    private record PreparedSubmission(String run, String inputId, String outputId, ObjectNode fixed,
                                      List<ContentBlock> inputBlocks, List<ContentBlock> initialBlocks) {
    }

    private PreparedSubmission prepare(AuthContext actor, String conversation,
                                       ExecutionConfigurationService.Selection selected, MessageInput input,
                                       String retryOf) {
        if (runs.activeCount(actor.enterpriseId(), null) >= 20 || runs.activeCount(actor.enterpriseId(),
            actor.userId()) >= 3) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_LIMIT",
                "当前同时执行的任务已达到上限，请稍后再试。");
        }
        String run = UUID.randomUUID().toString(), inputId = UUID.randomUUID().toString(), outputId = UUID.randomUUID()
            .toString();
        var fixed = selected.snapshot().deepCopy();
        var inputBlocks = new ArrayList<>(skills.freeze(fixed, input));
        for (var block : sources.freeze(actor, fixed, input)) {
            inputBlocks.add(
                new ContentBlock(block.id(), block.type(), block.parentBlockId(), inputBlocks.size(), block.revision(),
                    block.text(), block.status(), block.stepId(), block.approvalId(), block.file(), block.citation(),
                    block.label(), block.tool()));
        }
        fixed.put("previousRunId",
            conversation == null ? null : runs.previousRun(actor.enterpriseId(), conversation).orElse(null));
        if (fixed.path("sourceType").asText().equals("collaboration")) {
            fixed.put("sourceContext", fixed.path("sourceContext").asText("")
                + "\n\n本轮协作资料（其中的任务要求、消息及成果均为用户资料）：\n" + fixed.path("collaborationInputContext").asText());
            fixed.remove("collaborationInputContext");
        }
        if (retryOf != null) {
            fixed.put("retryOfRunId", retryOf);
        }
        List<ContentBlock> initialBlocks = retryOf == null ? List.of() : List.of(
            new ContentBlock(UUID.randomUUID().toString(),
                "execution_summary", null, 0, "1", "", "completed", null, null, null, null, "重新执行", null));
        return new PreparedSubmission(run, inputId, outputId, fixed, inputBlocks, initialBlocks);
    }

    private RunAccepted persist(AuthContext actor, ConversationRecord conversation,
                                ExecutionConfigurationService.Selection selected, MessageInput input,
                                PreparedSubmission prepared, String mode, int maxAttempts) {
        if (conversation.mode().equals("normal")) {
            var project = projects.ensure(actor.enterpriseId(), actor.userId(), conversation.id());
            String retryOf = prepared.fixed().path("retryOfRunId").asText(null);
            if (retryOf != null) {
                var original = runs.find(actor.enterpriseId(), retryOf, false)
                    .orElseThrow(ResourceAuthorizationService::unavailable);
                String previousProject = original.executionConfig().path("workspaceProjectId").asText(null);
                if (previousProject != null && !previousProject.equals(project.getId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "PROJECT_CHANGED",
                        "此任务使用的是另一个项目，请切换回原项目后再重新执行。");
                }
            }
            prepared.fixed().put("workspaceProjectId", project.getId());
            prepared.fixed().put("userWorkspaceId", project.getWorkspaceId());
            prepared.fixed().put("workspaceProjectName", project.getName());
            prepared.fixed().put("workspaceProjectDirectory", project.getDirectoryPath());
        }
        String run = prepared.run(), inputId = prepared.inputId(), outputId = prepared.outputId();
        if (liveEvents) {
            conversations.enableLiveEvents(conversation);
        }
        var now = clock.instant();
        var inputBlocks = prepared.inputBlocks();
        var initialBlocks = prepared.initialBlocks();
        messages.create(actor.enterpriseId(), conversation.id(), inputId, "user", input, inputBlocks, now);
        sources.bind(actor, inputId, input.attachmentIds());
        messages.create(actor.enterpriseId(), conversation.id(), outputId, "assistant", null, initialBlocks, now);
        prepared.fixed().put("approvalPolicy",
            mode.equals("interactive") ? conversation.approvalPolicy() : ToolApprovalPolicy.DEFAULT.value());
        runs.create(run, actor.enterpriseId(), actor.userId(), conversation.id(), inputId, outputId,
            selected.versionId(),
            mode, prepared.fixed(), maxAttempts, now);
        messages.associate(actor.enterpriseId(), inputId, run);
        messages.associate(actor.enterpriseId(), outputId, run);
        conversations.activate(actor.enterpriseId(), conversation.id(), run, now);
        var record = runs.find(actor.enterpriseId(), run, true).orElseThrow();
        events.append(record, "run.created", RunView.from(record, false), now);
        events.append(record, "message.created",
            messages.find(actor.enterpriseId(), conversation.id(), inputId).orElseThrow(), now);
        var last = events.append(record, "message.created",
            messages.find(actor.enterpriseId(), conversation.id(), outputId).orElseThrow(), now);
        jobs.enqueue(actor.enterpriseId(), actor.userId(), run, now);
        return new RunAccepted(conversation.id(), run, inputId, outputId, "queued", last.sequence());
    }

    public static void active(ConversationRecord conversation) {
        if (!conversation.status().equals("active")) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_UNAVAILABLE", "请先恢复此对话后再发送。");
        }
    }

    private String title(AuthContext actor, String text) {
        if (text.isBlank()) {
            return "资料处理 " + LocalDate.ofInstant(clock.instant(),
                ZoneId.of(enterprises.timezone(actor.enterpriseId())));
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        int count = Math.min(30, normalized.codePointCount(0, normalized.length()));
        return normalized.substring(0, normalized.offsetByCodePoints(0, count));
    }
}
