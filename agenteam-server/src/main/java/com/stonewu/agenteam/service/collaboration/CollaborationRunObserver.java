package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FileTextMapper;
import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationExecutionContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.service.chat.ChatPublicLinks;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** 独立观察既有 Run 状态，避免在执行终态事务中反向调用协作调度。 */
@Service
public class CollaborationRunObserver {
    private final CollaborationRunLinkMapper links;
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper workRows;
    private final CollaborationArtifactMapper artifactRows;
    private final EnterpriseMapper enterprises;
    private final RunMapper runs;
    private final ToolCallMapper calls;
    private final ExecutionMessageMapper messages;
    private final CollaborationAccessService access;
    private final CollaborationWorkService works;
    private final CollaborationDependencyService dependencies;
    private final CollaborationActionService actions;
    private final CollaborationArtifactService artifacts;
    private final CollaborationResultService results;
    private final ChatOutboundService outbound;
    private final ChatPublicLinks publicLinks;
    private final ResourceJson json;
    private final Clock clock;
    private final ExecutionToolCatalog tools;
    private final FileMapper files;
    private final FileTextMapper texts;

    public CollaborationRunObserver(CollaborationRunLinkMapper links, CollaborationTaskMapper tasks, CollaborationWorkItemMapper workRows,
            CollaborationArtifactMapper artifactRows, EnterpriseMapper enterprises, RunMapper runs, ToolCallMapper calls,
            ExecutionMessageMapper messages, CollaborationAccessService access, CollaborationWorkService works,
            CollaborationDependencyService dependencies, CollaborationActionService actions, CollaborationArtifactService artifacts,
            CollaborationResultService results, ChatOutboundService outbound, ChatPublicLinks publicLinks, ResourceJson json, Clock clock,
            ExecutionToolCatalog tools, FileMapper files, FileTextMapper texts) {
        this.links = links;
        this.tasks = tasks;
        this.workRows = workRows;
        this.artifactRows = artifactRows;
        this.enterprises = enterprises;
        this.runs = runs;
        this.calls = calls;
        this.messages = messages;
        this.access = access;
        this.works = works;
        this.dependencies = dependencies;
        this.actions = actions;
        this.artifacts = artifacts;
        this.results = results;
        this.outbound = outbound;
        this.publicLinks = publicLinks;
        this.json = json;
        this.clock = clock;
        this.tools = tools;
        this.files = files;
        this.texts = texts;
    }

    public List<CollaborationRunLinkRow> pending() {
        return links.selectPage(new Page<CollaborationRunLinkRow>(1, 50, false), new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .isNull(CollaborationRunLinkRow::getTerminalObservedAt).orderByAsc(CollaborationRunLinkRow::getUpdatedAt, CollaborationRunLinkRow::getId)).getRecords();
    }

    @Transactional
    public void observe(String enterprise, String id) {
        observe(enterprise, id, null);
    }

    @Transactional
    public void observe(String enterprise, String id, CollaborationGeneratedResultService.Prepared generated) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getId, id).isNull(CollaborationRunLinkRow::getTerminalObservedAt));
        if (link == null) {
            return;
        }
        var run = runs.find(enterprise, link.getRunId(), false).orElseThrow();
        var task = access.require(enterprise, link.getTaskId());
        var work = works.require(task, link.getWorkId());
        if (!run.terminal()) {
            if (!link.getLastStatus().equals(run.status())) {
                changedStatus(task, work, link, run);
            }
            links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
                .eq(CollaborationRunLinkRow::getId, id).set(CollaborationRunLinkRow::getLastStatus, run.status())
                .set(CollaborationRunLinkRow::getUpdatedAt, clock.instant()));
            return;
        }
        boolean recorded = Set.of("handed_off", "completed", "waiting_dependency", "waiting_input", "ready", "blocked", "superseded").contains(work.getStatus());
        boolean uncertain = "TOOL_RESULT_UNKNOWN".equals(run.errorCode()) || calls.forRun(enterprise, run.id()).stream()
            .anyMatch(call -> !call.readOnly() && (call.status().equals("unknown") || call.submittedAt() != null
                && call.resultEncrypted() == null && call.status().equals("running")));
        if (uncertain && !work.getStatus().equals("handed_off")) {
            block(task, work, "TOOL_RESULT_UNKNOWN");
        } else if (task.getStatus().equals("cancelling") || task.getStatus().equals("cancelled") || task.getStatus().equals("completed")
            || "role_waived".equals(link.getRequestedStopReason())) {
            if (!recorded) {
                works.change(work, "cancelled");
            }
        } else if (!task.getInputRevision().equals(link.getInputRevision())) {
            boolean owner = task.getOwnerWorkId().equals(work.getId());
            if (!Set.of("handed_off", "completed", "superseded").contains(work.getStatus()) || owner && work.getStatus().equals("completed")) {
                works.change(work, owner ? "ready" : "blocked");
                workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, enterprise)
                    .eq(CollaborationWorkItemRow::getId, work.getId()).set(owner, CollaborationWorkItemRow::getInputRevision, task.getInputRevision())
                    .set(owner, CollaborationWorkItemRow::getDeadlineAt, task.getDeadlineAt())
                    .set(!owner, CollaborationWorkItemRow::getErrorCode, "TASK_INPUT_CHANGED")
                    .set(CollaborationWorkItemRow::getResumeReason, "input_changed").set(CollaborationWorkItemRow::getContextId, null));
            }
        } else if (!recorded && run.status().equals("completed")) {
            automaticResult(task, work, link, run, generated);
        } else if (!recorded) {
            works.change(work, run.status().equals("cancelled") ? "cancelled" : "failed");
            blockTask(task, run.errorCode() == null ? "COLLABORATION_RUN_STOPPED" : run.errorCode());
            workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, enterprise)
                .eq(CollaborationWorkItemRow::getId, work.getId()).set(CollaborationWorkItemRow::getErrorCode, run.errorCode()));
        }
        workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, enterprise)
            .eq(CollaborationWorkItemRow::getId, work.getId()).eq(CollaborationWorkItemRow::getActiveRunId, run.id())
            .set(CollaborationWorkItemRow::getActiveRunId, null).set(CollaborationWorkItemRow::getUpdatedAt, clock.instant()));
        if (links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getId, id).isNull(CollaborationRunLinkRow::getTerminalObservedAt)
            .set(CollaborationRunLinkRow::getTerminalObservedAt, clock.instant()).set(CollaborationRunLinkRow::getLastStatus, run.status())
            .set(CollaborationRunLinkRow::getUpdatedAt, clock.instant())) != 1) {
            throw new IllegalStateException("协作执行终态已经由其他进程处理");
        }
        wake(task);
    }

    @Transactional
    public void failedObservation(String enterprise, String id, String code) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getId, id).isNull(CollaborationRunLinkRow::getTerminalObservedAt));
        if (link == null) {
            return;
        }
        var run = runs.find(enterprise, link.getRunId(), false).orElseThrow();
        if (!run.terminal()) {
            return;
        }
        var task = access.require(enterprise, link.getTaskId());
        var work = works.require(task, link.getWorkId());
        if (!Set.of("handed_off", "completed", "superseded").contains(work.getStatus())) {
            block(task, work, code);
        }
        workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, enterprise)
            .eq(CollaborationWorkItemRow::getId, work.getId()).eq(CollaborationWorkItemRow::getActiveRunId, run.id())
            .set(CollaborationWorkItemRow::getActiveRunId, null));
        links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getId, id).set(CollaborationRunLinkRow::getTerminalObservedAt, clock.instant())
            .set(CollaborationRunLinkRow::getLastStatus, run.status()));
    }

    private void automaticResult(CollaborationTaskRow task, CollaborationWorkItemRow work, CollaborationRunLinkRow link, RunRecord run,
            CollaborationGeneratedResultService.Prepared generated) {
        var role = access.participant(task, work.getParticipantId());
        var actor = access.requireArtifactRole(task, role);
        String text = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow().content();
        var existing = artifactRows.selectList(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationArtifactRow::getTaskId, task.getId()).eq(CollaborationArtifactRow::getProducerRunId, run.id())
            .eq(CollaborationArtifactRow::getStatus, "ready"));
        if (text.isBlank() && existing.isEmpty()) {
            block(task, work, "COLLABORATION_NO_RESULT");
            return;
        }
        var arguments = json.tree(Map.of("runId", run.id()));
        var action = actions.begin(task, work.getId(), run.id(), null, "run-result:" + run.id(), "run_result", arguments);
        var refs = new ArrayList<Map<String, Object>>();
        for (var artifact : existing) {
            refs.add(Map.of("artifactId", artifact.getId(), "version", artifact.getArtifactVersion()));
        }
        String summary = text.isBlank() ? "岗位已提交成果。" : text.codePointCount(0, text.length()) <= 2000 ? text : "岗位已完成本次工作，完整回答见成果。";
        if (!text.isBlank()) {
            if (json.tree(text).toString().getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                if (generated == null || !generated.contentHash().equals(json.hash(json.tree(text)))) {
                    throw new IllegalStateException("需要先在事务外准备完整回答文件");
                }
                var file = generated.file();
                if (!file.enterpriseId().equals(task.getEnterpriseId()) || !file.ownerUserId().equals(task.getInitiatorUserId())
                    || !file.runId().equals(run.id()) || !file.resourceId().equals(role.getAgentId())
                    || !file.resourceVersionId().equals(role.getAgentVersionId()) || !file.purpose().equals("collaboration_artifact")) {
                    throw new IllegalStateException("完整回答文件不属于本次岗位执行");
                }
                files.generated(file, clock.instant());
                int length = text.codePointCount(0, text.length());
                texts.save(files.find(task.getEnterpriseId(), file.id(), false).orElseThrow(),
                    new AttachmentText(text.substring(0, text.offsetByCodePoints(0, Math.min(50000, length))), length > 50000), clock.instant());
                var artifact = artifacts.publish(task, work, run.id(), "answer:" + work.getId(), role.getRoleName() + "完整回答", summary,
                    "file", null, file.id(), null);
                refs.add(Map.of("artifactId", artifact.getId(), "version", artifact.getArtifactVersion()));
            } else {
                var artifact = artifacts.publish(task, work, run.id(), "answer:" + work.getId(), role.getRoleName() + "完整回答", summary,
                    "text", json.tree(text), null, null);
                refs.add(Map.of("artifactId", artifact.getId(), "version", artifact.getArtifactVersion()));
            }
        }
        var context = new CollaborationExecutionContext(actor, task, work, role, link);
        if (work.getId().equals(task.getOwnerWorkId()) && !results.missingRoles(task).isEmpty()
            && (run.executionConfig().at("/config/agentType").asText().equals("workflow")
                || tools.all(run).values().stream().noneMatch(tool -> tool.definition().name().equals("collab_assign")))) {
            block(task, work, "COLLABORATION_PLUGIN_REQUIRED");
            actions.finish(action, Map.of("status", "blocked", "reason", "负责人没有配置团队协作插件，未安排其他明确指定岗位。"));
            return;
        }
        var input = json.tree(Map.of("summary", summary, "artifactRefs", refs));
        var result = results.submit(context, action, input, false, text);
        actions.finish(action, result);
    }

    private void changedStatus(CollaborationTaskRow task, CollaborationWorkItemRow work, CollaborationRunLinkRow link, RunRecord run) {
        if (run.status().equals("waiting_approval") && work.getStatus().equals("running")) {
            works.change(work, "waiting_approval");
            tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getStatus, "active")
                .set(CollaborationTaskRow::getStatus, "waiting_approval").setIncrBy(CollaborationTaskRow::getRevision, 1));
            outbound.text(task.getEnterpriseId(), task.getConversationId(), task.getEntryBotId(), task.getId(), null, "approval:" + run.id(),
                "waiting_approval", "任务 " + task.getShortCode() + " 有一项操作需要原发起人确认。\n" + publicLinks.task(task.getEnterpriseId(), task.getId()), null);
        } else if (run.status().equals("running") && work.getStatus().equals("waiting_approval")) {
            works.change(work, "running");
            tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getStatus, "waiting_approval")
                .set(CollaborationTaskRow::getStatus, "active").setIncrBy(CollaborationTaskRow::getRevision, 1));
        }
        if (run.status().equals("running") && run.startedAt() != null && link.getLastStatus().equals("queued")) {
            var role = access.participant(task, work.getParticipantId());
            outbound.progress(task.getEnterpriseId(), task.getConversationId(), role.getBotBindingId(), task.getId(), null, "started:" + run.id(),
                "started", "任务 " + task.getShortCode() + "：“" + role.getRoleName() + "”已开始处理。", null);
        }
    }

    private void wake(CollaborationTaskRow task) {
        for (var work : dependencies.works(task)) {
            if (work.getActiveRunId() == null && work.getStatus().equals("waiting_dependency")) {
                String readiness = dependencies.readiness(task, work.getId());
                boolean ownerBlocked = readiness.equals("blocked") && task.getOwnerWorkId().equals(work.getId());
                if (readiness.equals("ready") || ownerBlocked) {
                    works.change(work, "ready");
                    workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
                        .eq(CollaborationWorkItemRow::getId, work.getId()).set(CollaborationWorkItemRow::getResumeReason, "dependencies_ready")
                        .set(CollaborationWorkItemRow::getInputRevision, access.require(task.getEnterpriseId(), task.getId()).getInputRevision()));
                }
            }
        }
    }

    private void block(CollaborationTaskRow task, CollaborationWorkItemRow work, String code) {
        works.change(work, "blocked");
        workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getId, work.getId()).set(CollaborationWorkItemRow::getErrorCode, code));
        blockTask(task, code);
    }

    private void blockTask(CollaborationTaskRow task, String code) {
        if (code.equals("TOOL_RESULT_UNKNOWN")) {
            tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationTaskRow::getId, task.getId()).in(CollaborationTaskRow::getStatus, "completed", "cancelled", "cancelling")
                .set(CollaborationTaskRow::getErrorCode, code).setIncrBy(CollaborationTaskRow::getRevision, 1));
        }
        tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).notIn(CollaborationTaskRow::getStatus, "completed", "cancelled", "cancelling")
            .set(CollaborationTaskRow::getStatus, "blocked").set(CollaborationTaskRow::getErrorCode, code)
            .setIncrBy(CollaborationTaskRow::getRevision, 1));
    }
}
