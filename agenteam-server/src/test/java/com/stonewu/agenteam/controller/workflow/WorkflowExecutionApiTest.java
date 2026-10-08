package com.stonewu.agenteam.controller.workflow;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.test.execution.RunStepFixtureMapper;
import com.stonewu.agenteam.mapper.test.tool.ToolCallFixtureMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.*;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 正式提交、后台执行、真实模型及外部网页共同验收工作流，不用模拟节点完成替代业务结果。
 */
@Import(SharedEnterpriseTestEdition.class)
class WorkflowExecutionApiTest extends WorkflowExecutionApiTestSupport {

    @ParameterizedTest
    @ValueSource(ints = {0, 120})
    void entryWorkflowPersistsActualMappedResultWithoutCallingAModel(int timeoutSeconds) throws Exception {
        useWorkflow(linear(node("work", "transform", Map.of("fields", List.of(Map.of("target", "answer", "template", "收到：${input.text}")))), Map.of("text", "${steps.work.output.answer}")));
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", timeoutSeconds));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertCompleted(run);
            var snapshot = snapshot(accepted);
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals("收到：请整理这次活动的准备事项。", snapshot.at("/messages/1/content").asText());
            assertEquals(0, count("tool_call"));
            assertEquals(0, count("run_approval"));
            assertEquals(callsBefore, modelCalls.get());
            assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
            var steps = stepList(run);
            assertEquals(3, steps.stream().filter(item -> item.path("kind").asText().equals("workflow_node")).count());
            for (var step : steps) {
                schemas.validate("RunStep", step);
            }
            String output = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunStepFixtureMapper.class).workflowExecutionApiEntryWorkflowPersistsActualMappedResultWithoutCallingAModelObject(run));
            assertEquals("收到：请整理这次活动的准备事项。", json.readTree(output).path("answer").asText());
            assertNotNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunCheckpointSqlMapper.class).selectList(new LambdaQueryWrapper<RunCheckpointRow>().select(RunCheckpointRow::getFrameworkStateKey).eq(RunCheckpointRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getFrameworkStateKey()).toList()));
            validateEvents(accepted);
        }
    }

    @Test
    void rejectionResumesFromTheSavedApprovalAndNeverRunsTheOtherBranch() throws Exception {
        useWorkflow(graph(List.of(start(), node("approve", "approval", Map.of("title", "确认本次发布", "description", "核对提交的内容", "inputMapping", Map.of("text", "${input.text}"))), transform("yes", "不应执行"), transform("no", "本次已取消发布"), end(Map.of("text", "${steps.no.output.text}"))), List.of(edge("start", "approve"), edge("approve", "yes", "approve"), edge("approve", "no", "reject"), edge("yes", "end"), edge("no", "end"))));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", runStatus(run));
        assertEquals(0, count("tool_call"));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunApprovalSqlMapper.class).selectCount(new LambdaQueryWrapper<RunApprovalRow>().eq(RunApprovalRow::getRunId, (run)).isNotNull(RunApprovalRow::getToolCallId))));
        var confirmation = approvals(run).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + confirmation.path("id").asText() + "/decision", Map.of("decision", "reject", "requestHash", confirmation.path("requestHash").asText()), confirmation.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals("本次已取消发布", snapshot(accepted).at("/messages/1/content").asText());
        assertEquals(1, count("run_approval"));
        assertEquals("skipped", stepList(run).stream().filter(item -> item.at("/workflow/nodeId").asText().equals("yes")).findFirst().orElseThrow().path("status").asText());
        assertEquals(4, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
        validateEvents(accepted);
    }

    @Test
    void twoNodeAgentsRunConcurrentlyAndKeepDistinctRepliesUnderTheirNodes() throws Exception {
        String nodeVersion = version;
        var entered = new CountDownLatch(2);
        allowModelCalls = true;
        modelResponse = exchange -> {
            String request = json.readTree(exchange.getRequestBody()).toString();
            entered.countDown();
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("两条实际模型请求没有并行开始");
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IOException("模型验收被中断", stopped);
            }
            frame(exchange, request.contains("左侧问题") ? "左侧真实结果" : "右侧真实结果");
        };
        useWorkflow(graph(List.of(start(), node("parallel", "parallel", Map.of("joinNodeId", "join")), node("left", "agent", Map.of("agentVersionId", nodeVersion, "inputMapping", Map.of("text", "左侧问题"))), node("right", "agent", Map.of("agentVersionId", nodeVersion, "inputMapping", Map.of("text", "右侧问题"))), node("join", "join", Map.of("parallelNodeId", "parallel")), end(Map.of("text", "${steps.left.output.text}；${steps.right.output.text}"))), List.of(edge("start", "parallel"), edge("parallel", "left"), edge("parallel", "right"), edge("left", "join"), edge("right", "join"), edge("join", "end"))));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(0, entered.getCount());
        assertEquals(callsBefore + 2, modelCalls.get());
        var snapshot = snapshot(accepted);
        assertEquals("左侧真实结果；右侧真实结果", snapshot.at("/messages/1/content").asText());
        var orders = new HashSet<Integer>();
        for (var block : snapshot.at("/messages/1/blocks")) {
            assertTrue(orders.add(block.path("displayOrder").asInt()));
        }
        for (String id : List.of("left", "right")) {
            var step = stepList(run).stream().filter(value -> value.at("/workflow/nodeId").asText().equals(id)).findFirst().orElseThrow();
            assertTrue(snapshot.at("/messages/1/blocks").findValuesAsText("parentBlockId").contains(step.path("id").asText()));
            String output = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunStepSqlMapper.class).selectList(new LambdaQueryWrapper<RunStepRow>().select(RunStepRow::getOutputJson).eq(RunStepRow::getId, (step.path("id").asText()))).stream().map(fixtureRecord -> fixtureRecord.getOutputJson()).toList());
            assertEquals(id.equals("left") ? "左侧真实结果" : "右侧真实结果", json.readTree(output).path("text").asText());
        }
        validateEvents(accepted);
    }

    @Test
    void directReadNodeUsesTheActualPublishedToolAndItsExistingCallRecord() throws Exception {
        String plugin = publish(create("plugin", json.readTree("""
            {"icon":"Box","color":"blue","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """)));
        useWorkflow(linear(node("work", "tool", Map.of("pluginVersionId", plugin, "toolName", "read_url", "inputMapping", Map.of("url", origin() + "/page"))), Map.of("result", "${steps.work.output}")));
        int before = READS.get();
        var accepted = submit();
        String run = accepted.path("runId").asText();
        var checkpointRetried = new AtomicBoolean();
        doAnswer(call -> {
            JobLease lease = call.getArgument(0);
            if (lease.runId().equals(run) && DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunStepFixtureMapper.class).workflowExecutionApiDirectReadNodeUsesTheActualPublishedToolAndItsExistingCallRecordObject(run)) > 0 && checkpointRetried.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("测试模拟节点完成后的检查点暂时无法保存");
            }
            return call.callRealMethod();
        }).when(checkpointRecords).save(any(), anyString(), any(), any());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(before + 1, READS.get());
        assertEquals(1, count("tool_call"));
        assertEquals(0, count("run_approval"));
        assertTrue(checkpointRetried.get(), "验收必须实际触发节点结果与检查点事务重试");
        assertEquals("succeeded", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getStatus).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertTrue(snapshot(accepted).at("/messages/1/content").asText().contains("工作流读取到的实际网页"));
        assertFalse(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getFrameworkSessionId).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getFrameworkSessionId()).toList()).equals(accepted.path("conversationId").asText()));
        validateEvents(accepted);
    }

    @Test
    void explicitlyContinuedFailureRemainsVisibleInTheRunAndStructuredOutput() throws Exception {
        var work = node("work", "transform", Map.of("fields", List.of(Map.of("target", "text", "source", "input.missing"))));
        work.put("failurePolicy", "continue");
        useWorkflow(linear(work, Map.of("result", "${steps.work.output}")));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertTrue(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getHasStepErrors).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> (fixtureRecord.getHasStepErrors() != null && fixtureRecord.getHasStepErrors() != 0)).toList()));
        assertTrue(snapshot(accepted).at("/messages/1/content").asText().contains("WORKFLOW_INPUT_MISSING"));
        assertEquals("failed", stepList(run).stream().filter(value -> value.at("/workflow/nodeId").asText().equals("work")).findFirst().orElseThrow().path("status").asText());
        validateEvents(accepted);
    }

    @Test
    void workflowApprovalDoesNotApproveTheFollowingExternalWrite() throws Exception {
        var writes = new AtomicInteger();
        var received = new AtomicReference<JsonNode>();
        String plugin = remotePlugin(writes, received);
        useWorkflow(graph(List.of(start(), node("approval", "approval", Map.of("title", "继续处理记录", "description", "核对本次需求", "inputMapping", Map.of("text", "流程确认正文", "password", "workflow-approval-test-secret"))), node("write", "tool", Map.of("pluginVersionId", plugin, "toolName", "Read_Item", "inputMapping", Map.of("text", "真正提交的固定正文"))), transform("rejected", "已拒绝流程"), end(Map.of("text", "本次流程已结束"))), List.of(edge("start", "approval"), edge("approval", "write", "approve"), edge("approval", "rejected", "reject"), edge("write", "end"), edge("rejected", "end"))));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        waitForDecision(run);
        var first = approvals(run).get(0);
        assertEquals(0, count("tool_call"));
        assertFalse(first.toString().contains("workflow-approval-test-secret"));
        assertTrue(first.toString().contains("已隐藏"));
        decide(first, "approve");
        waitForDecision(run);
        assertEquals(0, writes.get());
        assertEquals(1, count("tool_call"));
        var all = approvals(run);
        assertEquals(2, all.size());
        JsonNode second = null;
        for (var approval : all) {
            if (approval.path("status").asText().equals("pending")) {
                second = approval;
            }
        }
        assertNotNull(second);
        assertTrue(second.at("/summary/content").asText().contains("真正提交的固定正文"));
        assertFalse(first.path("requestHash").equals(second.path("requestHash")));
        decide(second, "approve");
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(1, writes.get());
        assertEquals("真正提交的固定正文", received.get().path("text").asText());
        assertEquals(2, count("run_approval"));
        validateEvents(accepted);
    }

    @Test
    void transientReadFailuresRetryAtTwoAndTenSecondsAndRemainOneRecordedOperation() throws Exception {
        String plugin = readPlugin();
        useWorkflow(linear(node("work", "tool", Map.of("pluginVersionId", plugin, "toolName", "read_url", "inputMapping", Map.of("url", origin() + "/retry"))), Map.of("result", "${steps.work.output}")));
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", 0));
        READ_FAILURES.set(2);
        int before = READS.get();
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(22);
            while (!terminal(run) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        assertCompleted(run);
        assertEquals(before + 3, READS.get());
        assertEquals(3, READ_TIMES.size());
        assertTrue(READ_TIMES.get(1) - READ_TIMES.get(0) >= TimeUnit.MILLISECONDS.toNanos(1900));
        assertTrue(READ_TIMES.get(2) - READ_TIMES.get(1) >= TimeUnit.MILLISECONDS.toNanos(9900));
        assertEquals(1, count("tool_call"));
        assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getAttemptCount).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getAttemptCount()).toList()));
        assertEquals(6, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
        validateEvents(accepted);
    }

    @Test
    void rejectedReadsAndUnknownWritesAreNeverAutomaticallyResent() throws Exception {
        String read = readPlugin();
        useWorkflow(linear(node("work", "tool", Map.of("pluginVersionId", read, "toolName", "read_url", "inputMapping", Map.of("url", origin() + "/rejected"))), Map.of("text", "不应返回")));
        READ_FAILURES.set(3);
        readFailureStatus = 404;
        int before = READS.get();
        var first = submit();
        String firstRun = first.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(firstRun));
        }
        assertEquals("failed", runStatus(firstRun));
        assertEquals(before + 1, READS.get());
        assertEquals("REMOTE_REQUEST_REJECTED", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (firstRun))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
        var writes = new AtomicInteger();
        String plugin = remotePlugin(writes, new AtomicReference<>());
        REMOTE.toolResult = input -> {
            writes.incrementAndGet();
            throw new IllegalStateException("测试目标已收到请求但未返回结果");
        };
        var work = node("work", "tool", Map.of("pluginVersionId", plugin, "toolName", "Read_Item", "inputMapping", Map.of("text", "只发送一次")));
        work.put("failurePolicy", "continue");
        useWorkflow(linear(work, Map.of("text", "未知写入不能继续")));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        waitForDecision(run);
        decide(approvals(run).get(0), "approve");
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            worker.poll();
        }
        assertEquals("failed", runStatus(run));
        assertEquals(1, writes.get());
        assertEquals("TOOL_RESULT_UNKNOWN", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
        write(base() + "/runs/" + run + "/retry", null, UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    @Test
    void retryWaitSurvivesWorkerReplacementWithoutResettingItsAttemptsOrBudget() throws Exception {
        String plugin = readPlugin();
        useWorkflow(linear(node("work", "tool", Map.of("pluginVersionId", plugin, "toolName", "read_url", "inputMapping", Map.of("url", origin() + "/resume"))), Map.of("text", "读取已完成")));
        READ_FAILURES.set(1);
        int before = READS.get();
        var accepted = submit();
        String run = accepted.path("runId").asText();
        var old = new RunWorker(lifecycle, tasks);
        try (var replacement = new RunWorker(lifecycle, tasks)) {
            old.poll();
            await(() -> Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, (run)).eq(ToolCallRow::getAttemptCount, 1).eq(ToolCallRow::getStatus, "prepared"))) == 1);
            databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, ("run:" + run)).set(BackgroundJobRow::getLeaseUntil, (Timestamp.from(Instant.now().minusSeconds(1)))));
            replacement.poll();
            old.close();
            await(() -> terminal(run));
        } finally {
            old.close();
        }
        assertCompleted(run);
        assertEquals(before + 2, READS.get());
        assertEquals(1, count("tool_call"));
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getAttemptCount).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getAttemptCount()).toList()));
        assertEquals(5, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
        validateEvents(accepted);
    }

    @Test
    void persistentReadFailureStopsAfterTwoRetries() throws Exception {
        String plugin = readPlugin();
        useWorkflow(linear(node("work", "tool", Map.of("pluginVersionId", plugin, "toolName", "read_url", "inputMapping", Map.of("url", origin() + "/unavailable"))), Map.of("text", "不应继续")));
        READ_FAILURES.set(5);
        int before = READS.get();
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(22);
            while (!terminal(run) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            worker.poll();
        }
        assertEquals("failed", runStatus(run));
        assertEquals(before + 3, READS.get());
        assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getAttemptCount).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getAttemptCount()).toList()));
        assertEquals("REMOTE_SERVER_UNAVAILABLE", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
    }

    @Test
    void cancellationDuringRetryWaitPreventsTheNextRequest() throws Exception {
        String plugin = readPlugin();
        useWorkflow(linear(node("work", "tool", Map.of("pluginVersionId", plugin, "toolName", "read_url", "inputMapping", Map.of("url", origin() + "/cancel"))), Map.of("text", "不应返回")));
        READ_FAILURES.set(5);
        int before = READS.get();
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, (run)).eq(ToolCallRow::getAttemptCount, 1).eq(ToolCallRow::getStatus, "prepared"))) == 1);
            write(base() + "/runs/" + run + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
            worker.poll();
            await(() -> terminal(run));
            TimeUnit.MILLISECONDS.sleep(2200);
            worker.poll();
        }
        assertEquals("cancelled", runStatus(run));
        assertEquals(before + 1, READS.get());
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getAttemptCount).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getAttemptCount()).toList()));
    }

    @Test
    void parallelNodeAgentsResumeTheirOwnToolConfirmationsWithTheSameFrameworkCallIdentifier() throws Exception {
        var writes = new AtomicInteger();
        String plugin = remotePlugin(writes, new AtomicReference<>());
        configureAgent(config -> config.withArray("pluginVersionIds").add(plugin));
        String nodeVersion = version;
        var turns = new ConcurrentHashMap<String, AtomicInteger>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            String side = request.toString().contains("左侧请求") ? "左侧" : "右侧";
            if (turns.computeIfAbsent(side, ignored -> new AtomicInteger()).getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "same-call", "type", "function", "function", Map.of("name", modelTool(request, "Read_Item"), "arguments", json.writeValueAsString(Map.of("text", side + "固定正文")))))), "tool_calls");
            } else {
                frame(exchange, side + "处理结果");
            }
        };
        useWorkflow(graph(List.of(start(), node("parallel", "parallel", Map.of("joinNodeId", "join")), node("left", "agent", Map.of("agentVersionId", nodeVersion, "inputMapping", Map.of("text", "左侧请求"))), node("right", "agent", Map.of("agentVersionId", nodeVersion, "inputMapping", Map.of("text", "右侧请求"))), node("join", "join", Map.of("parallelNodeId", "parallel")), end(Map.of("text", "${steps.left.output.text}；${steps.right.output.text}"))), List.of(edge("start", "parallel"), edge("parallel", "left"), edge("parallel", "right"), edge("left", "join"), edge("right", "join"), edge("join", "end"))));
        var accepted = submit();
        String run = accepted.path("runId").asText();
        waitForDecision(run);
        var confirmations = approvals(run);
        assertEquals(2, confirmations.size());
        assertEquals(0, writes.get());
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallFixtureMapper.class).workflowExecutionApiParallelNodeAgentsResumeTheirOwnToolConfirmationsWithTheSameFrameworkCallIdentifierObject(run)));
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallFixtureMapper.class).scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject17(run)));
        for (var confirmation : confirmations) {
            decide(confirmation, confirmation.at("/summary/content").asText().contains("左侧") ? "approve" : "reject");
        }
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(1, writes.get());
        assertEquals(callsBefore + 4, modelCalls.get());
        assertEquals("左侧处理结果；右侧处理结果", snapshot(accepted).at("/messages/1/content").asText());
        validateEvents(accepted);
    }
}
